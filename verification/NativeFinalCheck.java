package com.mani.orbit;
import java.lang.reflect.Method;
public final class NativeFinalCheck {
    public static void main(String[] args) throws Exception {
        if (!MusicSession.playbackName(7).equals("error") || !MusicSession.playbackName(6).equals("buffering")
                || !MusicSession.playbackName(8).equals("buffering") || !MusicSession.playbackName(3).equals("playing")
                || !MusicSession.playbackName(2).equals("paused") || !MusicSession.playbackName(0).equals("unavailable"))
            throw new AssertionError("Media playback states must remain distinct");
        Method altitude = WorkoutSession.class.getDeclaredMethod("validAltitude", double.class);
        Method make = WorkoutSession.class.getDeclaredMethod("resumeToken");
        Method valid = WorkoutSession.class.getDeclaredMethod("validResumeToken", String.class);
        Method same = WorkoutSession.class.getDeclaredMethod("sameResumeToken", String.class, String.class);
        altitude.setAccessible(true); make.setAccessible(true); valid.setAccessible(true); same.setAccessible(true);
        if (!((Boolean) altitude.invoke(null, -12000.0)) || !((Boolean) altitude.invoke(null, -100.0))
                || ((Boolean) altitude.invoke(null, -12000.1)) || ((Boolean) altitude.invoke(null, 100000.1))
                || ((Boolean) altitude.invoke(null, Double.NaN))) throw new AssertionError("altitude validation failed");
        String first = (String) make.invoke(null), second = (String) make.invoke(null);
        if (first.length() != 32 || second.length() != 32 || first.equals(second)
                || !((Boolean) valid.invoke(null, first)) || !((Boolean) valid.invoke(null, second))
                || !((Boolean) same.invoke(null, first, first)) || ((Boolean) same.invoke(null, first, second))
                || ((Boolean) valid.invoke(null, first.substring(0, 31) + "g"))) throw new AssertionError("resume token validation failed");
        double oneDegree = WorkoutLocationMath.distanceMeters(0, 0, 0, 1);
        if (!(oneDegree > 111000 && oneDegree < 111300)) throw new AssertionError("distance validation failed: " + oneDegree);
        int[] full = MusicSession.artworkSize(4000, 3000), small = MusicSession.artworkSize(320, 180), tall = MusicSession.artworkSize(1, 100000);
        if (full[0] != 2048 || full[1] != 1536 || small[0] != 320 || small[1] != 180 || tall[0] != 1 || tall[1] != 2048) throw new AssertionError("artwork size bounds failed");
        try { MusicSession.artworkSize(0, 200); throw new AssertionError("invalid artwork size accepted"); } catch (IllegalArgumentException expected) { }
        System.out.println("artwork_2048_aspect_ratio_no_upscale=PASS");
        System.out.println("altitude_validation=PASS");
        System.out.println("resume_token_validation=PASS");
        System.out.println("distance_one_degree_m=" + oneDegree);
    }
}
