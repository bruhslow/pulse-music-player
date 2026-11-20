package com.slowbag.pulse.client.gui;

public class AnimationUtil {

    public static float lerp(float current, float target, float speed) {
        return current + (target - current) * Math.max(0.0f, Math.min(1.0f, speed));
    }

    public static float easeOutCubic(float x) {
        float inv = 1.0f - x;
        return 1.0f - inv * inv * inv;
    }

    public static float easeInOutQuad(float x) {
        return x < 0.5f ? 2.0f * x * x : 1.0f - (float) Math.pow(-2.0f * x + 2.0f, 2.0f) / 2.0f;
    }

    public static int colorLerp(int colorA, int colorB, float t) {
        t = Math.max(0.0f, Math.min(1.0f, t));
        int aA = (colorA >> 24) & 0xFF;
        int rA = (colorA >> 16) & 0xFF;
        int gA = (colorA >> 8) & 0xFF;
        int bA = colorA & 0xFF;

        int aB = (colorB >> 24) & 0xFF;
        int rB = (colorB >> 16) & 0xFF;
        int gB = (colorB >> 8) & 0xFF;
        int bB = colorB & 0xFF;

        int a = (int) (aA + (aB - aA) * t);
        int r = (int) (rA + (rB - rA) * t);
        int g = (int) (gA + (gB - gA) * t);
        int b = (int) (bA + (bB - bA) * t);

        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
