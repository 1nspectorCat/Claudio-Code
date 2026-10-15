package com.vladiko.voicebridge;

import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ActivityController;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HeldCertificate;
import okhttp3.tls.HandshakeCertificates;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class ReleaseReadinessTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private MockWebServer server;

    @Before public void createActivity() {
        RuntimeEnvironment.getApplication().getSharedPreferences("cfg", 0).edit().clear().commit();
        controller = Robolectric.buildActivity(MainActivity.class).create();
        activity = controller.get();
    }

    @After public void finish() throws Exception {
        Cfg.INSTANCE.setToken("");
        if (controller != null) controller.destroy();
        if (server != null) server.shutdown();
    }

    private Object field(String name) throws Exception {
        Field f = MainActivity.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(activity);
    }
    private void field(String name, Object value) throws Exception {
        Field f = MainActivity.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(activity, value);
    }
    private void render(String name, JSONObject state) throws Exception {
        Method m = MainActivity.class.getDeclaredMethod(name, JSONObject.class, boolean.class);
        m.setAccessible(true);
        m.invoke(activity, state, false);
    }
    private boolean sendCommand() throws Exception {
        Method m = MainActivity.class.getDeclaredMethod("sendHubCommand", JSONObject.class,
            String.class, String.class, String.class, boolean.class, long.class);
        m.setAccessible(true);
        return (Boolean)m.invoke(activity, new JSONObject().put("cmd", "plan.set"),
            "plan.set", "test", "test command", false, 60000L);
    }
    private TextView findText(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView)root).getText())) return (TextView)root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)root;
            for (int i=0; i<group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
    private boolean containsText(View root, String text) {
        if (root instanceof TextView && ((TextView)root).getText().toString().contains(text)) return true;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)root;
            for (int i=0; i<group.getChildCount(); i++) if (containsText(group.getChildAt(i), text)) return true;
        }
        return false;
    }

    @Test public void malformedServerArraysDoNotCrashAnyHubScreen() throws Exception {
        JSONObject state = new JSONObject("{\"agents\":[null,1,false],\"channels\":[null,\"bad\"]," +
            "\"projects\":[null,7],\"logins\":[null,true],\"providers\":[null,3]," +
            "\"subscriptions\":[null,false],\"money\":{\"subscriptions\":[null,1]," +
            "\"volume\":{\"bad\":false},\"quality\":{\"bad\":null}}}");
        for (String screen : new String[]{"renderHubChannels", "renderConnections", "renderModels", "renderMoney"})
            render(screen, state);
    }

    @Test public void neuroflashCardShowsSafeQuotaModelAndCredentialState() throws Exception {
        field("hubKeyProvider", "neuroflash");
        JSONObject provider = new JSONObject("{\"provider\":\"neuroflash\",\"label\":\"Neuroflash\"," +
            "\"needs_key\":true,\"models\":[{\"id\":\"nf-model-a\",\"available\":true}," +
            "null,7,{\"id\":\"nf-hidden-model\",\"available\":false}]," +
            "\"quotas\":[null,7,{\"usageTypeKey\":\"requests\",\"usedAmountDaily\":99}," +
            "{\"usageTypeKey\":\"tokens\",\"usedAmountDaily\":2.5,\"limitAmountDaily\":10," +
            "\"usedAmount\":18,\"limitAmount\":100,\"fairUseLimitReached\":true}]," +
            "\"checked_at\":1791549000}");
        render("renderConnections", new JSONObject().put("providers", new org.json.JSONArray().put(provider)));

        View root = (View)field("sheetList");
        assertTrue(containsText(root, "nf-model-a"));
        assertFalse(containsText(root, "nf-hidden-model"));
        assertTrue(containsText(root, "2.50 / 10.00"));
        assertTrue(containsText(root, "18.00 / 100.00"));
        assertTrue(containsText(root, "fair use limit reached"));
        String checkedDate = new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
            .format(new java.util.Date(1791549000L * 1000L));
        assertTrue(containsText(root, checkedDate));

        EditText clientSecret = (EditText)field("hubNfClientSecretEdit");
        assertNotNull(clientSecret);
        assertEquals(android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
            clientSecret.getInputType() & android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        assertFalse(clientSecret.isSaveEnabled());
    }

    @Test public void rejectedCommandsKeepTheDraftAndDoNotPretendToSend() throws Exception {
        Cfg.INSTANCE.setToken("synthetic-token");
        Cfg.INSTANCE.setPin("0".repeat(64));
        Cfg.INSTANCE.setUrl("not a URL");
        field("planEditSub", "claude");
        JSONObject state = new JSONObject("{\"money\":{\"subscriptions\":[{\"name\":\"claude\",\"price\":20}]}}");
        render("renderMoney", state);
        EditText price = (EditText)field("planPriceEdit");
        price.setText("23.50");
        TextView save = findText((View)field("sheetList"), "сохранить");
        assertNotNull(save);
        save.performClick();
        assertEquals("23.50", price.getText().toString());
        assertEquals("claude", field("planEditSub"));
        assertFalse(sendCommand());
        assertFalse((Boolean)field("textSending"));
    }

    @Test public void localFailureIsVisibleBeforeHubHasEverReplied() throws Exception {
        field("hubReq", "synthetic-req");
        field("hubReqText", "test command");
        field("hubReqError", "synthetic failure");
        for (String screen : new String[]{"renderHubChannels", "renderConnections", "renderModels", "renderMoney"}) {
            render(screen, null);
            assertTrue(screen, containsText((View)field("sheetList"), "synthetic failure"));
        }
    }

    @Test public void rotationKeepsFileDestinationPendingRequestAndTextDraft() throws Exception {
        field("fileTargetSid", "hub-synthetic");
        field("fileTargetName", "test");
        field("hubReq", "pending-req");
        field("hubReqCmd", "channel.create");
        field("hubReqTs", System.currentTimeMillis());
        field("hubReqWaitMs", 600000L);
        ((EditText)field("textEdit")).setText("unsent synthetic draft");
        Bundle state = new Bundle();
        controller.saveInstanceState(state).destroy();
        controller = Robolectric.buildActivity(MainActivity.class).create(state);
        activity = controller.get();
        assertEquals("hub-synthetic", field("fileTargetSid"));
        assertEquals("pending-req", field("hubReq"));
        assertEquals("unsent synthetic draft", ((EditText)field("textEdit")).getText().toString());
        Method busy = MainActivity.class.getDeclaredMethod("hubBusy");
        busy.setAccessible(true);
        assertTrue((Boolean)busy.invoke(activity));
    }

    private void connectSyntheticRelay() throws Exception {
        server = new MockWebServer();
        HeldCertificate cert = new HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build();
        HandshakeCertificates tls = new HandshakeCertificates.Builder().heldCertificate(cert).build();
        server.useHttps(tls.sslSocketFactory(), false);
        server.start();
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(cert.certificate().getEncoded());
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b & 255));
        Cfg.INSTANCE.setPin(hex.toString());
        Cfg.INSTANCE.setToken("synthetic-token");
        Cfg.INSTANCE.setUrl(server.url("/").toString().replaceAll("/$", ""));
        field("feedSid", "hub-synthetic");
    }

    @Test public void successfulAckDoesNotEraseTextEditedWhileSending() throws Exception {
        connectSyntheticRelay();
        server.enqueue(new MockResponse().setBody("{\"unread\":false}")
            .setHeadersDelay(300, TimeUnit.MILLISECONDS));
        EditText edit = (EditText)field("textEdit");
        edit.setText("first message");
        Method send = MainActivity.class.getDeclaredMethod("sendTyped");
        send.setAccessible(true);
        send.invoke(activity);
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
        edit.setText("next draft");
        long until = System.currentTimeMillis() + 5000;
        while ((Boolean)field("textSending") && System.currentTimeMillis() < until) {
            Thread.sleep(20);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
        assertFalse((Boolean)field("textSending"));
        assertEquals("next draft", edit.getText().toString());
        assertTrue(containsText((View)field("textStatus"), "✓"));
    }
}
