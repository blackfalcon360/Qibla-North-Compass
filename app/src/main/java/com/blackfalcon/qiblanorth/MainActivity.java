package com.blackfalcon.qiblanorth;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

public class MainActivity extends Activity implements SensorEventListener, LocationListener {

    private QiblaView view;
    private SensorManager sm;
    private Sensor rot;
    private LocationManager lm;
    private SharedPreferences sp;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timeout;

    private final float[] rotMat = new float[9];
    private final float[] remapped = new float[9];
    private final float[] orient = new float[3];
    private float sinAvg = 0f, cosAvg = 1f;
    private boolean first = true;

    private float declination = 0f;
    private boolean hasLoc = false;
    private boolean searching = false;
    private boolean wasSearching = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new QiblaView(this);
        view.setListener(this::refreshLocation);
        setContentView(view);
        hideSystemUi();

        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        rot = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        sp = getSharedPreferences("qibla", MODE_PRIVATE);
        if (rot == null) view.setSensorMissing(true);

        // last saved location -> works immediately, even with no GPS and no internet
        if (sp.contains("lat") && sp.contains("lon")) {
            applyLocation(Double.longBitsToDouble(sp.getLong("lat", 0)), Double.longBitsToDouble(sp.getLong("lon", 0)));
            view.setStatus(QiblaView.ST_SAVED);
        } else {
            view.setStatus(QiblaView.ST_NONE);
        }

        if (hasPerm()) {
            refreshLocation();
        } else {
            view.setStatus(QiblaView.ST_NOPERM);
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (rot != null) sm.registerListener(this, rot, SensorManager.SENSOR_DELAY_GAME);
        if (wasSearching) { wasSearching = false; refreshLocation(); }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sm.unregisterListener(this);
        wasSearching = searching;
        stopLocation();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (hasPerm()) refreshLocation(); else view.setStatus(QiblaView.ST_NOPERM);
    }

    private boolean hasPerm() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ------------------------------------------------------------ location
    private void refreshLocation() {
        if (!hasPerm()) {
            view.setStatus(QiblaView.ST_NOPERM);
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            }
            return;
        }
        stopLocation();

        boolean any = false;
        try {
            for (String pr : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (lm.isProviderEnabled(pr)) {
                    lm.requestLocationUpdates(pr, 0, 0, this);
                    any = true;
                }
            }
            if (!hasLoc) {
                Location last = null;
                for (String pr : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                    Location l = lm.getLastKnownLocation(pr);
                    if (l != null && (last == null || l.getTime() > last.getTime())) last = l;
                }
                if (last != null) applyLocation(last.getLatitude(), last.getLongitude());
            }
        } catch (SecurityException ignored) { }

        if (!any) {
            view.setStatus(QiblaView.ST_NOGPS);
            return;
        }

        searching = true;
        view.setStatus(QiblaView.ST_SEARCHING);
        timeout = () -> {
            if (!searching) return;
            stopLocation();
            view.setStatus(QiblaView.ST_NOFIX);
        };
        handler.postDelayed(timeout, 25000);
    }

    private void stopLocation() {
        searching = false;
        if (timeout != null) { handler.removeCallbacks(timeout); timeout = null; }
        try { lm.removeUpdates(this); } catch (SecurityException ignored) { }
    }

    private void applyLocation(double la, double lo) {
        hasLoc = true;
        declination = new GeomagneticField((float) la, (float) lo, 0f, System.currentTimeMillis()).getDeclination();
        view.setLocation(la, lo);
    }

    @Override
    public void onLocationChanged(Location l) {
        if (!searching) return;
        stopLocation();
        sp.edit()
                .putLong("lat", Double.doubleToRawLongBits(l.getLatitude()))
                .putLong("lon", Double.doubleToRawLongBits(l.getLongitude()))
                .apply();
        applyLocation(l.getLatitude(), l.getLongitude());
        view.setStatus(QiblaView.ST_LIVE);
    }

    @Override public void onStatusChanged(String p, int s, Bundle e) { }
    @Override public void onProviderEnabled(String p) { }
    @Override public void onProviderDisabled(String p) { }

    // -------------------------------------------------------------- compass
    @SuppressWarnings("deprecation")
    private int displayRotation() {
        return getWindowManager().getDefaultDisplay().getRotation();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        SensorManager.getRotationMatrixFromVector(rotMat, event.values);

        int xAxis, yAxis;
        switch (displayRotation()) {
            case Surface.ROTATION_90:
                xAxis = SensorManager.AXIS_Y; yAxis = SensorManager.AXIS_MINUS_X; break;
            case Surface.ROTATION_180:
                xAxis = SensorManager.AXIS_MINUS_X; yAxis = SensorManager.AXIS_MINUS_Y; break;
            case Surface.ROTATION_270:
                xAxis = SensorManager.AXIS_MINUS_Y; yAxis = SensorManager.AXIS_X; break;
            default:
                xAxis = SensorManager.AXIS_X; yAxis = SensorManager.AXIS_Y; break;
        }
        SensorManager.remapCoordinateSystem(rotMat, xAxis, yAxis, remapped);
        SensorManager.getOrientation(remapped, orient);

        float azimuth = (float) Math.toDegrees(orient[0]) + declination; // true north
        double rad = Math.toRadians(azimuth);
        float s = (float) Math.sin(rad), c = (float) Math.cos(rad);
        if (first) { sinAvg = s; cosAvg = c; first = false; }
        else { sinAvg += 0.15f * (s - sinAvg); cosAvg += 0.15f * (c - cosAvg); }

        float heading = (float) Math.toDegrees(Math.atan2(sinAvg, cosAvg));
        if (heading < 0) heading += 360f;
        view.setHeading(heading);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}
