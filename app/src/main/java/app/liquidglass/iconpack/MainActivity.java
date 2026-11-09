package app.liquidglass.iconpack;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView label(String text, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), getColor(R.color.pack_stroke));
        return drawable;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(getColor(R.color.pack_background));
        getWindow().setNavigationBarColor(getColor(R.color.pack_background));
        getWindow().getDecorView().setSystemUiVisibility(
                getResources().getBoolean(R.bool.pack_light_status_bar)
                        ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.pack_background));
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(26), dp(22), dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        TextView eyebrow = label("ICON PACK  ·  ANDROID", 11, getColor(R.color.pack_accent), true);
        eyebrow.setLetterSpacing(0.11f);
        content.addView(eyebrow);

        TextView title = label(getString(R.string.pack_title), 30,
                getColor(R.color.pack_text_primary), true);
        title.setPadding(0, dp(8), 0, dp(5));
        content.addView(title);

        TextView subtitle = label(getString(R.string.pack_description), 14,
                getColor(R.color.pack_text_secondary), false);
        subtitle.setLineSpacing(dp(3), 1.0f);
        content.addView(subtitle);

        LinearLayout guide = new LinearLayout(this);
        guide.setOrientation(LinearLayout.VERTICAL);
        guide.setPadding(dp(16), dp(15), dp(16), dp(15));
        guide.setBackground(rounded(getColor(R.color.pack_surface), 22));
        LinearLayout.LayoutParams guideParams = new LinearLayout.LayoutParams(-1, -2);
        guideParams.setMargins(0, dp(20), 0, dp(16));
        content.addView(guide, guideParams);
        TextView guideTitle = label("Pasang di launcher", 16,
                getColor(R.color.pack_text_primary), true);
        guide.addView(guideTitle);
        TextView guideText = label("1. Instal APK varian ini.\n2. Buka pengaturan launcher.\n3. Pilih Appearance / Icon style → Icon pack → "
                + getString(R.string.pack_short_name) + ".", 13,
                getColor(R.color.pack_text_secondary), false);
        guideText.setLineSpacing(dp(4), 1.0f);
        guideText.setPadding(0, dp(8), 0, dp(13));
        guide.addView(guideText);
        TextView note = label("Membutuhkan launcher yang mendukung icon pack, misalnya Nova, Lawnchair, Hyperion, ADW, atau Smart Launcher.",
                11, getColor(R.color.pack_text_secondary), false);
        note.setLineSpacing(dp(2), 1.0f);
        guide.addView(note);

        LinearLayout section = new LinearLayout(this);
        section.setGravity(Gravity.CENTER_VERTICAL);
        TextView sectionTitle = label("Pratinjau ikon", 19,
                getColor(R.color.pack_text_primary), true);
        section.addView(sectionTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView count = label(getResources().getStringArray(R.array.icon_labels).length + " ikon",
                12, getColor(R.color.pack_text_secondary), false);
        section.addView(count);
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(-1, -2);
        sectionParams.setMargins(0, dp(7), 0, dp(12));
        content.addView(section, sectionParams);

        String[] names = getResources().getStringArray(R.array.icon_names);
        String[] labels = getResources().getStringArray(R.array.icon_labels);
        float widthDp = getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density;
        int columns = widthDp < 390 ? 3 : widthDp < 600 ? 4 : 5;
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(columns);
        grid.setUseDefaultMargins(false);
        for (int i = 0; i < names.length; i++) {
            final String drawableName = names[i];
            final String appName = labels[i];
            int id = getResources().getIdentifier(drawableName, "drawable", getPackageName());
            if (id == 0) continue;

            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(3), dp(7), dp(3), dp(7));
            cell.setBackground(rounded(getColor(R.color.pack_tile), 19));
            ImageView icon = new ImageView(this);
            icon.setImageResource(id);
            icon.setContentDescription(appName);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cell.addView(icon, new LinearLayout.LayoutParams(dp(58), dp(58)));
            TextView name = label(appName, 10.5f, getColor(R.color.pack_text_primary), false);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, dp(25));
            nameParams.topMargin = dp(4);
            cell.addView(name, nameParams);
            cell.setOnClickListener(v -> showIconDetails(id, appName));
            cell.setContentDescription(appName + " icon preview");
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f));
            params.width = 0;
            params.height = dp(103);
            params.setMargins(dp(3), dp(3), dp(3), dp(3));
            grid.addView(cell, params);
        }
        content.addView(grid, new LinearLayout.LayoutParams(-1, -2));

        TextView footer = label("Liquid Glass art original, terinspirasi tampilan iOS. Bukan produk atau paket resmi Apple.",
                11, getColor(R.color.pack_text_secondary), false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(8), dp(20), dp(8), 0);
        content.addView(footer);
        setContentView(scroll);
    }

    private void showIconDetails(int iconId, String appName) {
        ImageView preview = new ImageView(this);
        preview.setImageResource(iconId);
        preview.setPadding(dp(20), dp(8), dp(20), dp(8));
        preview.setAdjustViewBounds(true);
        new AlertDialog.Builder(this)
                .setTitle(appName)
                .setView(preview)
                .setPositiveButton("Tutup", null)
                .show();
    }
}
