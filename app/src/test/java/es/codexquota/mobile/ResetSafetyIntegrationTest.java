package es.codexquota.mobile;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Looper;
import es.codexquota.mobile.core.PendingReset;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {OfflineIntegrationFixture.OfflineTokens.class, OfflineIntegrationFixture.OfflineQuotaClient.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class ResetSafetyIntegrationTest extends OfflineIntegrationFixture {
    private ActivityController<MainActivity> activity;

    private MainActivity open(String action) {
        Intent intent = new Intent(context, MainActivity.class);
        if (action != null) intent.setAction(action);
        activity = Robolectric.buildActivity(MainActivity.class, intent).create().start().resume().visible();
        shadowOf(Looper.getMainLooper()).idle();
        return activity.get();
    }

    @After public void closeActivity() {
        if (activity != null) activity.pause().stop().destroy();
    }

    @Test public void appResetRequiresExplicitConfirmationAndCancelDoesNotConsume() throws Exception {
        MainActivity screen = open(null);
        ReflectionHelpers.callInstanceMethod(screen, "confirmReset");
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        String explanation = ((android.widget.TextView) dialog.findViewById(android.R.id.message)).getText().toString();
        assertTrue(explanation, explanation.contains("Renovación automática"));
        assertTrue(explanation, explanation.contains("Crédito próximo"));
        assertTrue(explanation, explanation.contains("5 horas"));
        assertEquals("Usar 1 reset", dialog.getButton(DialogInterface.BUTTON_POSITIVE).getText().toString());
        assertTrue(Server.requestIds.isEmpty());
        assertFalse(repository.hasPendingReset());
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        finishIo();
        assertTrue("Cancel must never reach the consume API", Server.requestIds.isEmpty());
        assertFalse(repository.hasPendingReset());
    }

    @Test public void widgetResetIntentShowsConfirmationWithoutSendingRequest() throws Exception {
        open(MainActivity.SHOW_RESET);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        assertTrue(Server.requestIds.isEmpty());
        dialog.cancel();
        finishIo();
        assertTrue(Server.requestIds.isEmpty());
    }

    @Test public void repeatedWidgetIntentNeverBypassesExistingDialog() {
        MainActivity screen = open(MainActivity.SHOW_RESET);
        AlertDialog first = ShadowAlertDialog.getLatestAlertDialog();
        screen.onNewIntent(new Intent(context, MainActivity.class).setAction(MainActivity.SHOW_RESET));
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(first.isShowing());
        assertSame(first, ShadowAlertDialog.getLatestAlertDialog());
        assertTrue(Server.requestIds.isEmpty());
    }

    @Test public void confirmingDialogConsumesOnlyEarliestCredit() throws Exception {
        open(MainActivity.SHOW_RESET);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        finishIo();
        assertEquals(1, Server.requestIds.size());
        assertEquals("earliest", Server.creditIds.get(0));
        assertFalse(repository.hasPendingReset());
    }

    @Test public void uncertainOutcomeRequiresNewConfirmationAndKeepsIdempotency() throws Exception {
        QuotaRepository.ResetApproval approved = repository.prepareReset();
        Server.failConsume = true;
        consume(approved);
        assertTrue(repository.hasPendingReset());
        assertEquals(1, Server.requestIds.size());
        String originalRequest = Server.requestIds.get(0);
        MainActivity screen = open(null);
        ReflectionHelpers.callInstanceMethod(screen, "confirmReset");
        AlertDialog retry = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("Reintentar", retry.getButton(DialogInterface.BUTTON_POSITIVE).getText().toString());
        assertEquals("Opening a retry must not send it", 1, Server.requestIds.size());
        retry.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        finishIo();
        assertEquals(1, Server.requestIds.size());
        ReflectionHelpers.callInstanceMethod(screen, "confirmReset");
        Server.failConsume = false;
        ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        finishIo();
        assertEquals(2, Server.requestIds.size());
        assertEquals(originalRequest, Server.requestIds.get(1));
        assertEquals("earliest", Server.creditIds.get(1));
        assertFalse(repository.hasPendingReset());
    }

    @Test public void changedCreditInvalidatesApprovalWithoutPosting() throws Exception {
        QuotaRepository.ResetApproval approved = repository.prepareReset();
        ReflectionHelpers.setField(repository, "snapshot", snapshot(now, "replacement", now + TimeUnit.HOURS.toMillis(2)));
        String message = consume(approved);
        assertTrue(message, message.contains("han cambiado"));
        assertTrue(Server.requestIds.isEmpty());
        assertFalse(repository.hasPendingReset());
    }

    @Test public void signedOutSessionCannotUseAnOldApproval() throws Exception {
        QuotaRepository.ResetApproval approved = repository.prepareReset();
        repository.logout();
        finishIo();
        consume(approved);
        assertTrue(Server.requestIds.isEmpty());
    }

    @Test public void pendingRequestSurvivesRestartAndIsOfferedOnlyAsRetry() throws Exception {
        QuotaRepository.ResetApproval approved = repository.prepareReset();
        Server.failConsume = true;
        consume(approved);
        PendingReset first = repository.prepareReset().request;
        assertTrue(repository.hasPendingReset());
        stopOfflineSession();
        repository = QuotaRepository.get(context);
        QuotaRepository.ResetApproval retry = repository.prepareReset();
        assertTrue(retry.retry);
        assertTrue(first.sameRequest(retry.request));
        assertEquals(1, Server.requestIds.size());
    }
}
