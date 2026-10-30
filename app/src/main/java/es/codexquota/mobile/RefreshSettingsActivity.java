package es.codexquota.mobile;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import es.codexquota.mobile.core.RefreshPolicy;

public final class RefreshSettingsActivity extends Activity {
    private int border;
    private int buttonColor;
    private TextView condition;
    private boolean dirty;
    private int ink;
    private int muted;
    private QuotaRepository repo;
    private SeekBar slider;
    private TextView status;
    private int surface;
    private boolean tracking;
    private TextView value;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable commitValue = new Runnable() {
        @Override
        public final void run() {
            RefreshSettingsActivity.this.commitPending();
        }
    };

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        this.repo = QuotaRepository.get(this);
        render();
    }

    @Override
    public void onPause() {
        this.handler.removeCallbacks(this.commitValue);
        commitPending();
        this.tracking = false;
        super.onPause();
    }

    @Override
    public void onDestroy() {
        this.handler.removeCallbacks(this.commitValue);
        super.onDestroy();
    }

    private void render() {
        boolean z = (getResources().getConfiguration().uiMode & 48) == 32;
        this.ink = z ? Color.rgb(245, 245, 245) : Color.rgb(24, 24, 24);
        int i = z ? 163 : 100;
        this.muted = Color.rgb(i, i, i);
        this.surface = z ? Color.rgb(32, 32, 32) : -1;
        int i2 = z ? 58 : 229;
        this.border = Color.rgb(i2, i2, i2);
        this.buttonColor = z ? Color.rgb(48, 48, 48) : Color.rgb(237, 237, 237);
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(z ? Color.rgb(23, 23, 23) : Color.rgb(247, 247, 247));
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setPadding(dp(20), dp(20), dp(20), dp(24));
        scrollView.addView(linearLayout);
        setContentView(scrollView);
        if (Build.VERSION.SDK_INT >= 30) {
            scrollView.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public final WindowInsets onApplyWindowInsets(View view, WindowInsets windowInsets) {
                    return RefreshSettingsActivity.applySystemInsets(view, windowInsets);
                }
            });
            scrollView.requestApplyInsets();
        }
        Button button = new Button(this);
        button.setText("‹ Volver");
        button.setAllCaps(false);
        button.setTextSize(15.0f);
        button.setTextColor(this.ink);
        button.setMinHeight(dp(48));
        button.setBackground(background(this.buttonColor, 10));
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public final void onClick(View view) {
                RefreshSettingsActivity.this.closeScreen(view);
            }
        });
        linearLayout.addView(button);
        TextView textViewText = text("›_  CODEX · BETA", 14, this.ink, true);
        textViewText.setTypeface(Typeface.MONOSPACE);
        margin(textViewText, 18);
        linearLayout.addView(textViewText);
        View viewText = text("Actualización", 28, this.ink, true);
        margin(viewText, 16);
        linearLayout.addView(viewText);
        View viewText2 = text("Elige cada cuánto se comprueba tu cuota y los resets.", 15, this.muted, false);
        margin(viewText2, 8);
        linearLayout.addView(viewText2);
        LinearLayout linearLayout2 = new LinearLayout(this);
        linearLayout2.setOrientation(LinearLayout.VERTICAL);
        linearLayout2.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable gradientDrawableBackground = background(this.surface, 14);
        gradientDrawableBackground.setStroke(dp(1), this.border);
        linearLayout2.setBackground(gradientDrawableBackground);
        margin(linearLayout2, 18);
        linearLayout.addView(linearLayout2);
        linearLayout2.addView(text("Frecuencia de actualización", 17, this.ink, true));
        TextView textViewText2 = text("", 24, this.ink, true);
        this.value = textViewText2;
        textViewText2.setTypeface(Typeface.MONOSPACE);
        margin(this.value, 12);
        linearLayout2.addView(this.value);
        SeekBar seekBar = new SeekBar(this);
        this.slider = seekBar;
        seekBar.setMax(23);
        this.slider.setProgress((this.repo.refreshIntervalMinutes() - 15) / 15);
        this.slider.setKeyProgressIncrement(1);
        this.slider.setProgressTintList(ColorStateList.valueOf(this.ink));
        this.slider.setThumbTintList(ColorStateList.valueOf(this.ink));
        this.slider.setProgressBackgroundTintList(ColorStateList.valueOf(this.border));
        linearLayout2.addView(this.slider, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout linearLayout3 = new LinearLayout(this);
        TextView textViewText3 = text("15 min", 12, this.muted, false);
        TextView textViewText4 = text("6 h", 12, this.muted, false);
        textViewText4.setGravity(8388613);
        linearLayout3.addView(textViewText3, new LinearLayout.LayoutParams(0, -2, 1.0f));
        linearLayout3.addView(textViewText4, new LinearLayout.LayoutParams(0, -2, 1.0f));
        linearLayout2.addView(linearLayout3);
        TextView textViewText5 = text("", 14, this.muted, false);
        this.condition = textViewText5;
        margin(textViewText5, 10);
        linearLayout2.addView(this.condition);
        TextView textViewText6 = text("", 13, this.muted, false);
        this.status = textViewText6;
        margin(textViewText6, 8);
        linearLayout2.addView(this.status);
        updateLabels();
        this.status.setText("Guardado · cada " + RefreshPolicy.label(this.repo.refreshIntervalMinutes()));
        this.slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar2, int i3, boolean z2) {
                RefreshSettingsActivity.this.updateLabels();
                if (z2) {
                    RefreshSettingsActivity.this.dirty = true;
                    RefreshSettingsActivity.this.status.setText(RefreshSettingsActivity.this.tracking ? "Se guarda al soltar la barra." : "Guardando…");
                    RefreshSettingsActivity.this.handler.removeCallbacks(RefreshSettingsActivity.this.commitValue);
                    if (RefreshSettingsActivity.this.tracking) {
                        return;
                    }
                    RefreshSettingsActivity.this.handler.postDelayed(RefreshSettingsActivity.this.commitValue, 350L);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar2) {
                RefreshSettingsActivity.this.tracking = true;
                RefreshSettingsActivity.this.handler.removeCallbacks(RefreshSettingsActivity.this.commitValue);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar2) {
                RefreshSettingsActivity.this.tracking = false;
                RefreshSettingsActivity.this.handler.removeCallbacks(RefreshSettingsActivity.this.commitValue);
                RefreshSettingsActivity.this.commitPending();
            }
        });
        View viewText3 = text("Se aplica a la app y a los dos widgets. Los avisos de cuota requieren una consulta nueva; los recordatorios de caducidad también usan las fechas guardadas.", 14, this.muted, false);
        margin(viewText3, 18);
        linearLayout.addView(viewText3);
        View viewText4 = text("Android puede retrasar las consultas y los recordatorios para ahorrar batería. Al abrir la app se comprueba si los datos están antiguos. Puedes usar Actualizar ahora en cualquier momento.", 13, this.muted, false);
        margin(viewText4, 12);
        linearLayout.addView(viewText4);
    }

    static WindowInsets applySystemInsets(View view, WindowInsets windowInsets) {
        if (Build.VERSION.SDK_INT >= 30) {
            Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
        } else {
            view.setPadding(windowInsets.getSystemWindowInsetLeft(), windowInsets.getSystemWindowInsetTop(),
                    windowInsets.getSystemWindowInsetRight(), windowInsets.getSystemWindowInsetBottom());
        }
        return windowInsets;
    }

        void closeScreen(View view) {
        finish();
    }

    private int selectedMinutes() {
        return (this.slider.getProgress() * 15) + 15;
    }

        public void updateLabels() {
        String strLabel = RefreshPolicy.label(selectedMinutes());
        this.value.setText(strLabel);
        this.condition.setText("Comprobar aproximadamente cada " + strLabel + ".");
        this.slider.setContentDescription("Frecuencia de actualización: cada " + strLabel);
    }

        public void commitPending() {
        if (this.slider == null || !this.dirty) {
            return;
        }
        if (this.repo.setRefreshIntervalMinutes(selectedMinutes())) {
            this.dirty = false;
            this.status.setText("Guardado · cada " + RefreshPolicy.label(this.repo.refreshIntervalMinutes()));
            return;
        }
        this.status.setText("No se pudo guardar. Vuelve a ajustar la barra.");
    }

    private TextView text(String str, int i, int i2, boolean z) {
        TextView textView = new TextView(this);
        textView.setText(str);
        textView.setTextSize(i);
        textView.setTextColor(i2);
        textView.setLineSpacing(dp(3), 1.0f);
        if (z) {
            textView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return textView;
    }

    private GradientDrawable background(int i, int i2) {
        GradientDrawable gradientDrawable = new GradientDrawable();
        gradientDrawable.setColor(i);
        gradientDrawable.setCornerRadius(dp(i2));
        return gradientDrawable;
    }

    private void margin(View view, int i) {
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
        layoutParams.topMargin = dp(i);
        view.setLayoutParams(layoutParams);
    }

    private int dp(int i) {
        return Math.round(i * getResources().getDisplayMetrics().density);
    }
}
