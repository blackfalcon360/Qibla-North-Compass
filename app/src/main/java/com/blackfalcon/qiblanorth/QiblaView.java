package com.blackfalcon.qiblanorth;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

public class QiblaView extends View {

    public interface Listener { void onRefresh(); }

    public static final int ST_NONE = 0, ST_SEARCHING = 1, ST_LIVE = 2, ST_SAVED = 3,
            ST_NOGPS = 4, ST_NOPERM = 5, ST_NOFIX = 6;

    // Kaaba, Makkah
    private static final double KAABA_LAT = 21.422487, KAABA_LON = 39.826206;
    private static final int GREEN = Color.parseColor("#2ECC71");
    private static final int RED = Color.parseColor("#FF3B30");
    private static final int GRAY = Color.parseColor("#9E9E9E");

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF btn = new RectF();
    private boolean btnDown = false;

    private Listener listener;
    private float heading = 0f;
    private boolean hasLoc = false, sensorMissing = false;
    private double lat, lon, qiblaBearing, distanceKm;
    private int status = ST_NONE;

    public QiblaView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        tp.setTextAlign(Paint.Align.CENTER);
    }

    public void setListener(Listener l) { listener = l; }
    public void setHeading(float h) { heading = h; invalidate(); }
    public void setSensorMissing(boolean m) { sensorMissing = m; invalidate(); }
    public void setStatus(int s) { status = s; invalidate(); }

    public void setLocation(double la, double lo) {
        lat = la; lon = lo; hasLoc = true;
        qiblaBearing = bearing(la, lo, KAABA_LAT, KAABA_LON);
        distanceKm = haversine(la, lo, KAABA_LAT, KAABA_LON);
        invalidate();
    }

    // ---- math (all offline) ----
    static double bearing(double la1, double lo1, double la2, double lo2) {
        double p1 = Math.toRadians(la1), p2 = Math.toRadians(la2), dl = Math.toRadians(lo2 - lo1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    static double haversine(double la1, double lo1, double la2, double lo2) {
        double r = 6371.0088;
        double dp = Math.toRadians(la2 - la1), dl = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2)) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static String dir8(float h) {
        String[] n = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return n[Math.round(h / 45f) % 8];
    }

    private String statusText() {
        switch (status) {
            case ST_SEARCHING: return hasLoc ? "Getting GPS fix\u2026 (showing saved location)" : "Getting GPS fix\u2026";
            case ST_LIVE: return "GPS location \u2022 works offline";
            case ST_SAVED: return "Saved location \u2022 tap Refresh to update";
            case ST_NOGPS: return "GPS is off \u2014 turn it on, then tap Refresh";
            case ST_NOPERM: return "Location permission needed";
            case ST_NOFIX: return hasLoc ? "No GPS fix \u2014 using saved location" : "No GPS fix yet \u2014 go outside and tap Refresh";
            default: return "Tap Refresh to get your location";
        }
    }

    // ---- drawing helpers ----
    private void text(Canvas c, String s, float x, float y, float size, int color, boolean bold) {
        tp.setTextSize(size);
        tp.setColor(color);
        tp.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        c.drawText(s, x, y, tp);
    }

    private float px(float cx, float r, float deg) { return cx + r * (float) Math.sin(Math.toRadians(deg)); }
    private float py(float cy, float r, float deg) { return cy - r * (float) Math.cos(Math.toRadians(deg)); }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        float unit = Math.min(w, h);
        boolean land = w > h;
        c.drawColor(Color.BLACK);

        float R, cx, cy;
        if (land) { R = Math.min(h * 0.40f, w * 0.26f); cx = w * 0.27f; cy = h * 0.5f; }
        else { R = Math.min(w * 0.40f, h * 0.26f); cx = w / 2f; cy = h * 0.04f + R * 1.15f + unit * 0.02f; }

        double diff = ((qiblaBearing - heading + 540.0) % 360.0) - 180.0; // +right, -left
        boolean aligned = hasLoc && Math.abs(diff) < 3.0;

        // ring
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(unit * (aligned ? 0.014f : 0.007f));
        p.setColor(aligned ? GREEN : Color.WHITE);
        c.drawCircle(cx, cy, R, p);

        // ticks (rotate with heading so true North points to North)
        for (int d = 0; d < 360; d += 5) {
            float a = d - heading;
            float len = (d % 30 == 0) ? R * 0.09f : R * 0.05f;
            p.setStrokeWidth(unit * (d % 30 == 0 ? 0.005f : 0.0025f));
            p.setColor(Color.WHITE);
            c.drawLine(px(cx, R, a), py(cy, R, a), px(cx, R - len, a), py(cy, R - len, a), p);
        }

        // labels
        String[] card = {"N", "E", "S", "W"};
        for (int i = 0; i < 4; i++) {
            float a = i * 90 - heading;
            float size = i == 0 ? R * 0.22f : R * 0.15f;
            text(c, card[i], px(cx, R * 0.74f, a), py(cy, R * 0.74f, a) + size * 0.35f, size, i == 0 ? RED : Color.WHITE, true);
        }
        for (int d = 30; d < 360; d += 30) {
            if (d % 90 == 0) continue;
            float a = d - heading;
            text(c, String.valueOf(d), px(cx, R * 0.74f, a), py(cy, R * 0.74f, a) + R * 0.03f, R * 0.085f, GRAY, false);
        }

        // North needle (red)
        float nA = -heading;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(unit * 0.009f);
        p.setColor(RED);
        c.drawLine(cx, cy, px(cx, R * 0.52f, nA), py(cy, R * 0.52f, nA), p);
        p.setStyle(Paint.Style.FILL);
        Path np = new Path();
        np.moveTo(px(cx, R * 0.60f, nA), py(cy, R * 0.60f, nA));
        np.lineTo(px(cx, R * 0.48f, nA - 7), py(cy, R * 0.48f, nA - 7));
        np.lineTo(px(cx, R * 0.48f, nA + 7), py(cy, R * 0.48f, nA + 7));
        np.close();
        c.drawPath(np, p);

        // Qibla needle (green) + Kaaba emoji
        if (hasLoc) {
            float qA = (float) qiblaBearing - heading;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(unit * 0.013f);
            p.setColor(GREEN);
            c.drawLine(cx, cy, px(cx, R * 0.80f, qA), py(cy, R * 0.80f, qA), p);
            text(c, "\uD83D\uDD4B", px(cx, R * 0.90f, qA), py(cy, R * 0.90f, qA) + R * 0.07f, R * 0.22f, Color.WHITE, false);
        }

        // centre dot
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        c.drawCircle(cx, cy, unit * 0.014f, p);

        // fixed marker = where the top of the phone points
        Path tri = new Path();
        float tw = unit * 0.03f;
        tri.moveTo(cx, cy - R - unit * 0.004f);
        tri.lineTo(cx - tw, cy - R - unit * 0.004f - tw * 1.6f);
        tri.lineTo(cx + tw, cy - R - unit * 0.004f - tw * 1.6f);
        tri.close();
        p.setColor(Color.WHITE);
        c.drawPath(tri, p);

        // ---- info panel ----
        float ix = land ? w * 0.68f : w / 2f;
        float y = land ? h * 0.20f : cy + R + unit * 0.11f;

        float s1 = unit * 0.085f;
        text(c, hasLoc ? String.format(Locale.US, "Qibla %.1f\u00B0", qiblaBearing) : "Qibla \u2014", ix, y, s1, GREEN, true);
        y += s1 * 1.35f;

        float s2 = unit * 0.058f;
        String turn;
        if (!hasLoc) turn = "Waiting for location";
        else if (aligned) turn = "\u2714 Facing Qibla";
        else if (diff > 0) turn = String.format(Locale.US, "Turn right %.0f\u00B0", diff);
        else turn = String.format(Locale.US, "Turn left %.0f\u00B0", -diff);
        text(c, turn, ix, y, s2, aligned ? GREEN : Color.WHITE, true);
        y += s2 * 1.5f;

        float s3 = unit * 0.05f;
        String dist = hasLoc ? String.format(Locale.US, "Distance to Kaaba: %,d km", Math.round(distanceKm)) : "Distance to Kaaba: \u2014";
        text(c, dist, ix, y, s3, Color.WHITE, false);
        y += s3 * 1.5f;

        String hd = sensorMissing ? "Compass sensor not found"
                : String.format(Locale.US, "Heading: %d\u00B0 %s", Math.round(heading) % 360, dir8(heading));
        text(c, hd, ix, y, s3, Color.WHITE, false);
        y += s3 * 1.5f;

        float s4 = unit * 0.038f;
        if (hasLoc) {
            String co = String.format(Locale.US, "%.4f\u00B0 %s, %.4f\u00B0 %s",
                    Math.abs(lat), lat >= 0 ? "N" : "S", Math.abs(lon), lon >= 0 ? "E" : "W");
            text(c, co, ix, y, s4, GRAY, false);
            y += s4 * 1.5f;
        }
        text(c, statusText(), ix, y, s4, GRAY, false);
        y += s4 * 1.5f;

        // ---- refresh button ----
        float bw = land ? unit * 0.75f : Math.min(w * 0.8f, unit * 0.75f);
        float bh = unit * 0.12f;
        float bTop = y + unit * 0.03f;
        btn.set(ix - bw / 2f, bTop, ix + bw / 2f, bTop + bh);
        p.setStyle(Paint.Style.FILL);
        p.setColor(btnDown ? Color.parseColor("#1B5E3A") : Color.parseColor("#0D1F14"));
        c.drawRoundRect(btn, bh / 2f, bh / 2f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(unit * 0.004f);
        p.setColor(GREEN);
        c.drawRoundRect(btn, bh / 2f, bh / 2f, p);
        text(c, "\u21BB  Refresh location", btn.centerX(), btn.centerY() + unit * 0.02f, unit * 0.05f, GREEN, true);

        // ---- credit ----
        tp.setTextAlign(Paint.Align.RIGHT);
        text(c, "By: Black Falcon \uD83E\uDD85", w - unit * 0.04f, h - unit * 0.04f, unit * 0.04f, Color.WHITE, true);
        tp.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean inside = btn.contains(e.getX(), e.getY());
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (inside) { btnDown = true; invalidate(); return true; }
                return false;
            case MotionEvent.ACTION_UP:
                if (btnDown) {
                    btnDown = false;
                    invalidate();
                    if (inside && listener != null) listener.onRefresh();
                    return true;
                }
                return false;
            case MotionEvent.ACTION_CANCEL:
                btnDown = false; invalidate(); return true;
            default:
                return btnDown;
        }
    }
}
