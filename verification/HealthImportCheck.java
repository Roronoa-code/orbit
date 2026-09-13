package com.mani.orbit;

import android.app.Activity;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.health.connect.*;
import android.health.connect.datatypes.*;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.units.*;
import org.json.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.Files;

/** Local emulator only. This fixture package supplies controlled Health Connect records. */
public final class HealthImportCheck extends Activity {
    private static Metadata metadata(String id) { return new Metadata.Builder().setClientRecordId("orbit-check-" + id).setClientRecordVersion(2).setRecordingMethod(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED).build(); }
    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); }
    private static <T> OutcomeReceiver<T, HealthConnectException> receiver(CompletableFuture<T> future) {
        return new OutcomeReceiver<T, HealthConnectException>() { public void onResult(T result) { future.complete(result); } public void onError(HealthConnectException error) { future.completeExceptionally(error); } };
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            try { run(); result("PASS: real Health Connect pagination, Samsung source filter, units, daily/workout aggregates, replay, cancellation, SQLite rollback and demo retirement"); }
            catch (Throwable error) { java.io.StringWriter log = new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(log)); result("FAIL: " + log); }
        }).start();
    }
    private void result(String text) {
        try { Files.write(getFileStreamPath("result.txt").toPath(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (Exception error) { android.util.Log.e("OrbitImportCheck", text, error); }
        android.util.Log.i("OrbitImportCheck", text);
    }
    private void run() throws Exception {
        Instant start = LocalDate.now().minusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant(), end = start.plusSeconds(12 * 3600);
        List<Record> records = new ArrayList<>();
        for (int i = 0; i < 501; i++) records.add(new StepsRecord.Builder(metadata("step-" + i), start.plusSeconds(i * 60), start.plusSeconds((i + 1) * 60), 10).build());
        records.add(new WeightRecord.Builder(metadata("weight"), start, Mass.fromGrams(75800)).build());
        records.add(new WeightRecord.Builder(metadata("older-weight"), start.minusSeconds(60 * 86400L), Mass.fromGrams(79000)).build());
        records.add(new ActiveCaloriesBurnedRecord.Builder(metadata("energy"), start, end, Energy.fromCalories(270000)).build());
        records.add(new DistanceRecord.Builder(metadata("distance"), start, end, Length.fromMeters(5710)).build());
        records.add(new HeartRateRecord.Builder(metadata("heart"), start, end, Arrays.asList(new HeartRateRecord.HeartRateSample(70,start.plusSeconds(10)),new HeartRateRecord.HeartRateSample(80,start.plusSeconds(20)))).build());
        records.add(new SleepSessionRecord.Builder(metadata("sleep"),start,end).setStages(Arrays.asList(new SleepSessionRecord.Stage(start,start.plusSeconds(3600),4),new SleepSessionRecord.Stage(start.plusSeconds(7200),end,5))).build());
        records.add(new ExerciseSessionRecord.Builder(metadata("workout"),start,end,ExerciseSessionType.EXERCISE_SESSION_TYPE_RUNNING).setTitle("Local fixture").build());
        HealthConnectManager manager = getSystemService(HealthConnectManager.class);
        if (!getIntent().getBooleanExtra("reader",false)) {
        CompletableFuture<InsertRecordsResponse> inserted = new CompletableFuture<>(); manager.insertRecords(records,Runnable::run,receiver(inserted));
        check(inserted.get(45,TimeUnit.SECONDS).getRecords().size()==508,"seed count including extended history");
        }
        HealthConnectReader reader = new HealthConnectReader(this); reader.visible=true;
        int expectedCount=reader.allowed(HealthConnectReader.HISTORY)?508:507;
        if (getDatabasePath("import-check.db").exists()) check(deleteDatabase("import-check.db"),"reset isolated check database");
        try (HealthRecordStore store = new HealthRecordStore(getDatabasePath("import-check.db"))) {
            reader.sync(store,(type,count)->android.util.Log.i("OrbitImportCheck",type+":"+count));
            check(store.metadata().getLong("recordCount")==expectedCount,"more than one page imported");
            JSONObject projection=HealthProjection.read(store,LocalDate.now().minusDays(1));
            Files.write(getFileStreamPath("projection.json").toPath(),projection.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            JSONObject workout=projection.getJSONArray("workouts").getJSONObject(0),summary=workout.getJSONObject("summary");
            check(summary.getDouble("distance")==5710,"workout distance");check(summary.getDouble("energy")==270,"calories to kcal");check(summary.getDouble("heartAverage")==75,"actual heart average");
            boolean weight=false,steps=false,distance=false,energy=false;
            JSONArray rows=projection.getJSONArray("rows");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.getString("type").equals("weight"))weight=Math.abs(row.getDouble("value")-75.8)<.0001;if(row.getString("type").equals("stepsDay"))steps=row.getLong("value")==5010;if(row.getString("type").equals("distanceDay"))distance=row.getDouble("value")==5710;if(row.getString("type").equals("energyDay"))energy=row.getDouble("value")==270;}
            check(weight&&steps&&distance&&energy,"daily totals at midnight, including extended history");
            check(reader.stepHours(LocalDate.now().minusDays(1)).length()>0,"real hourly data");
            reader.sync(store,(type,count)->{});check(store.metadata().getLong("recordCount")==expectedCount,"repeat import deduplicates");
            String before=store.metadata().toString();reader.visible=false;
            try{reader.sync(store,(type,count)->{});throw new AssertionError("hidden import allowed");}catch(CancellationException expected){}
            check(store.metadata().toString().equals(before),"cancellation preserves previous import");
            JSONObject invalid=new JSONObject(workout.toString()).put("source","unrelated.app");store.beginImport();
            try{store.stage(new JSONArray().put(workout).put(invalid));throw new AssertionError("foreign source accepted");}catch(IllegalArgumentException expected){}
            check(store.metadata().toString().equals(before),"failed staging preserves real data");
            check(store.firstStaged("exercise",-1)==-1,"failed batch rolled back completely");
            Files.write(getFileStreamPath("projection.json").toPath(),projection.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        android.content.SharedPreferences prefs=getSharedPreferences("orbit-workouts",MODE_PRIVATE),profile=getSharedPreferences("orbit-preferences",MODE_PRIVATE);
        String demo="{\"active\":null,\"history\":[{\"kind\":\"Strength\"}]}";
        check(prefs.edit().clear().putString("sessions",demo).commit(),"seed demo");check(profile.edit().putString("profile","keep").commit(),"seed profile");
        check(DemoRetirement.apply(this),"retire demo");check(prefs.getString("test-sessions-backup-v1","").equals(demo),"recoverable backup");
        check(new JSONObject(prefs.getString("sessions","")).getJSONArray("history").length()==0,"demo removed");
        check(profile.getString("profile","").equals("keep"),"profile preserved");
        check(prefs.edit().putString("sessions","new real session").commit(),"new session");check(DemoRetirement.apply(this)&&prefs.getString("sessions","").equals("new real session"),"future workouts survive");
    }
}
