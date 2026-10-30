package es.codexquota.mobile.core;

public final class ConsumeResult {
    public final String code;
    public final long windowsReset;

    public ConsumeResult(String str, long j) {
        this.code = str;
        this.windowsReset = j;
    }

    public String userMessage() {
        String str = this.code;
        str.hashCode();
        switch (str) {
            case "reset":
                return "Restablecimiento aplicado. Se han renovado " + this.windowsReset + " ventanas de cuota.";
            case "no_credit":
                return "No tienes un restablecimiento disponible.";
            case "nothing_to_reset":
                return "El servidor indica que no hay cuota que restablecer.";
            case "already_redeemed":
                return "Esta solicitud ya se había aplicado. No se ha usado otro restablecimiento.";
            default:
                return "El servidor devolvió un resultado nuevo. Actualiza la cuota antes de volver a intentarlo.";
        }
    }
}
