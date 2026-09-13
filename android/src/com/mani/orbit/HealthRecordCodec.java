package com.mani.orbit;

import android.health.connect.datatypes.*;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.units.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/** Platform values keep their original IDs, times, source and units; absent values stay absent. */
final class HealthRecordCodec {
    static final String SOURCE = "com.sec.android.app.shealth";
    static final Map<String, Class<? extends Record>> TYPES = new LinkedHashMap<>();
    static final Map<String, String> PERMISSIONS = new LinkedHashMap<>();
    static {
        type("steps", StepsRecord.class, "STEPS");
        type("distance", DistanceRecord.class, "DISTANCE");
        type("floors", FloorsClimbedRecord.class, "FLOORS_CLIMBED");
        type("energy", ActiveCaloriesBurnedRecord.class, "ACTIVE_CALORIES_BURNED");
        type("totalEnergy", TotalCaloriesBurnedRecord.class, "TOTAL_CALORIES_BURNED");
        type("heart", HeartRateRecord.class, "HEART_RATE");
        type("sleep", SleepSessionRecord.class, "SLEEP");
        type("nutrition", NutritionRecord.class, "NUTRITION");
        type("water", HydrationRecord.class, "HYDRATION");
        type("weight", WeightRecord.class, "WEIGHT");
        type("fat", BodyFatRecord.class, "BODY_FAT");
        type("lean", LeanBodyMassRecord.class, "LEAN_BODY_MASS");
        type("height", HeightRecord.class, "HEIGHT");
        type("oxygen", OxygenSaturationRecord.class, "OXYGEN_SATURATION");
        type("exercise", ExerciseSessionRecord.class, "EXERCISE");
    }
    private static void type(String key, Class<? extends Record> type, String permission) {
        TYPES.put(key, type); PERMISSIONS.put(key, "android.permission.health.READ_" + permission);
    }

    static JSONObject encode(String type, Record record) throws Exception {
        Metadata metadata = record.getMetadata();
        if (!SOURCE.equals(metadata.getDataOrigin().getPackageName())) throw new IllegalArgumentException("Unexpected health source");
        if (TYPES.get(type) != record.getClass()) throw new IllegalArgumentException("Unexpected record type");
        Instant start, end; ZoneOffset startOffset, endOffset;
        if (record instanceof IntervalRecord) {
            IntervalRecord interval = (IntervalRecord) record;
            start = interval.getStartTime(); end = interval.getEndTime(); startOffset = interval.getStartZoneOffset(); endOffset = interval.getEndZoneOffset();
        } else {
            InstantRecord instant = (InstantRecord) record;
            start = end = instant.getTime(); startOffset = endOffset = instant.getZoneOffset();
        }
        JSONObject row = new JSONObject().put("type", type).put("id", metadata.getId()).put("source", SOURCE)
            .put("start", start.toEpochMilli()).put("end", end.toEpochMilli()).put("modified", metadata.getLastModifiedTime().toEpochMilli())
            .put("recordingMethod", metadata.getRecordingMethod()).put("clientId", metadata.getClientRecordId()).put("clientVersion", metadata.getClientRecordVersion());
        if (startOffset != null) row.put("startOffset", startOffset.getTotalSeconds());
        if (endOffset != null) row.put("endOffset", endOffset.getTotalSeconds());
        Device device = metadata.getDevice();
        if (device != null) row.put("device", new JSONObject().put("manufacturer", device.getManufacturer()).put("model", device.getModel()).put("type", device.getType()));
        switch (type) {
            case "steps": row.put("value", ((StepsRecord) record).getCount()); break;
            case "distance": row.put("value", ((DistanceRecord) record).getDistance().getInMeters()); break;
            case "floors": row.put("value", ((FloorsClimbedRecord) record).getFloors()); break;
            case "energy": row.put("value", ((ActiveCaloriesBurnedRecord) record).getEnergy().getInCalories() / 1000); break;
            case "totalEnergy": row.put("value", ((TotalCaloriesBurnedRecord) record).getEnergy().getInCalories() / 1000); break;
            case "weight": row.put("value", ((WeightRecord) record).getWeight().getInGrams() / 1000); break;
            case "fat": row.put("value", ((BodyFatRecord) record).getPercentage().getValue()); break;
            case "lean": row.put("value", ((LeanBodyMassRecord) record).getMass().getInGrams() / 1000); break;
            case "height": row.put("value", ((HeightRecord) record).getHeight().getInMeters() * 100); break;
            case "oxygen": row.put("value", ((OxygenSaturationRecord) record).getPercentage().getValue()); break;
            case "water": row.put("value", ((HydrationRecord) record).getVolume().getInLiters() * 1000); break;
            case "heart": {
                JSONArray samples = new JSONArray();
                for (HeartRateRecord.HeartRateSample sample : ((HeartRateRecord) record).getSamples()) samples.put(new JSONArray().put(sample.getTime().toEpochMilli()).put(sample.getBeatsPerMinute()));
                row.put("samples", samples); break;
            }
            case "sleep": {
                SleepSessionRecord sleep = (SleepSessionRecord) record;
                JSONArray stages = new JSONArray();
                for (SleepSessionRecord.Stage stage : sleep.getStages()) stages.put(new JSONArray().put(stage.getStartTime().toEpochMilli()).put(stage.getEndTime().toEpochMilli()).put(stage.getType()));
                row.put("stages", stages).put("title", text(sleep.getTitle())).put("notes", text(sleep.getNotes())); break;
            }
            case "nutrition": {
                NutritionRecord meal = (NutritionRecord) record;
                row.put("name", meal.getMealName()).put("mealType", meal.getMealType());
                if (meal.getEnergy() != null) row.put("calories", meal.getEnergy().getInCalories() / 1000);
                putMass(row, "protein", meal.getProtein()); putMass(row, "carbs", meal.getTotalCarbohydrate()); putMass(row, "fat", meal.getTotalFat());
                // Preserve all reported nutrients, including those without a dashboard tile.
                JSONObject nutrients = new JSONObject();
                for (java.lang.reflect.Method method : NutritionRecord.class.getMethods()) if (method.getParameterTypes().length == 0 && method.getName().startsWith("get") && method.getReturnType() == Mass.class) {
                    Mass value = (Mass) method.invoke(meal); if (value != null) nutrients.put(method.getName().substring(3), value.getInGrams());
                }
                row.put("nutrientsGrams", nutrients); break;
            }
            case "exercise": {
                ExerciseSessionRecord session = (ExerciseSessionRecord) record;
                row.put("exerciseType", session.getExerciseType()).put("kind", exerciseName(session.getExerciseType())).put("title", text(session.getTitle())).put("notes", text(session.getNotes())).put("hasRoute", session.hasRoute());
                JSONArray laps = new JSONArray();
                for (ExerciseLap lap : session.getLaps()) { JSONObject value = new JSONObject().put("start", lap.getStartTime().toEpochMilli()).put("end", lap.getEndTime().toEpochMilli()); if (lap.getLength() != null) value.put("distanceM", lap.getLength().getInMeters()); laps.put(value); }
                JSONArray segments = new JSONArray();
                for (ExerciseSegment segment : session.getSegments()) segments.put(new JSONObject().put("start", segment.getStartTime().toEpochMilli()).put("end", segment.getEndTime().toEpochMilli()).put("type", segment.getSegmentType()).put("repetitions", segment.getRepetitionsCount()));
                row.put("laps", laps).put("segments", segments); break;
            }
            default: throw new IllegalArgumentException("Unsupported record");
        }
        return row;
    }

    static String day(long time) { return Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate().toString(); }
    static String text(CharSequence text) { return text == null ? null : text.toString(); }
    private static void putMass(JSONObject row, String key, Mass value) throws Exception { if (value != null) row.put(key, value.getInGrams()); }
    static String exerciseName(int type) {
        switch (type) {
            case 4: return "Cycling"; case 5: return "Indoor cycling"; case 33: return "Running"; case 34: return "Treadmill";
            case 45: case 55: return "Strength"; case 53: return "Walking"; case 48: return "Open-water swimming"; case 49: return "Swimming";
            case 20: return "HIIT"; case 21: return "Hiking"; case 57: return "Yoga"; case 58: return "Other workout";
            default:
                for (java.lang.reflect.Field field : ExerciseSessionType.class.getFields()) try {
                    if (field.getType() == int.class && field.getInt(null) == type) {
                        String words = field.getName().replace("EXERCISE_SESSION_TYPE_", "").replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
                        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
                    }
                } catch (IllegalAccessException error) { throw new IllegalStateException(error); }
                return "Workout (" + type + ")";
        }
    }
}
