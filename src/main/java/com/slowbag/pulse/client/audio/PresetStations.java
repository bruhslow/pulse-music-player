package com.slowbag.pulse.client.audio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PresetStations {
    private static final List<AudioTrack> STATIONS = new ArrayList<>();

    static {
        // High-availability 24/7 ad-free curated public streams with high-resolution album artwork
        STATIONS.add(new AudioTrack(
                "Lofi & Chillout Beats",
                "Groove Salad (SomaFM)",
                "https://ice1.somafm.com/groovesalad-128-mp3",
                true, -1, "Lofi / Chill",
                "https://somafm.com/img3/groovesalad120.png"
        ));

        STATIONS.add(new AudioTrack(
                "Vaporwave & Synthwave",
                "Nightwave Plaza 24/7",
                "https://radio.plaza.one/mp3",
                true, -1, "Synthwave",
                "https://plaza.one/apple-touch-icon.png"
        ));

        STATIONS.add(new AudioTrack(
                "Space Station Ambient",
                "Deep Ambient Space (SomaFM)",
                "https://ice1.somafm.com/spacestation-128-mp3",
                true, -1, "Ambient",
                "https://somafm.com/img3/spacestation120.png"
        ));

        STATIONS.add(new AudioTrack(
                "Smooth Cafe Jazz",
                "Radio Swiss Jazz",
                "https://stream.srg-ssr.ch/m/rsj/mp3_128",
                true, -1, "Jazz",
                "https://somafm.com/img3/secretagent120.png"
        ));

        STATIONS.add(new AudioTrack(
                "Peaceful Classical Piano",
                "Radio Swiss Classic",
                "https://stream.srg-ssr.ch/m/rsc_de/mp3_128",
                true, -1, "Classical",
                "https://somafm.com/img3/thistle120.png"
        ));

        STATIONS.add(new AudioTrack(
                "Cyberpunk & Hacking Beats",
                "DEF CON Radio (SomaFM)",
                "https://ice1.somafm.com/defcon-128-mp3",
                true, -1, "Electronic",
                "https://somafm.com/img3/defcon120.png"
        ));

        STATIONS.add(new AudioTrack(
                "Deep House & Chill Lounge",
                "Suburbs of Goa (SomaFM)",
                "https://ice1.somafm.com/suburbsofgoa-128-mp3",
                true, -1, "Lounge",
                "https://somafm.com/img3/suburbsofgoa120.png"
        ));

        STATIONS.add(new AudioTrack(
                "Lush Dream Pop & Vocals",
                "Lush Radio (SomaFM)",
                "https://ice1.somafm.com/lush-128-mp3",
                true, -1, "Vocal Chill",
                "https://somafm.com/img3/lush120.png"
        ));
    }

    public static List<AudioTrack> getStations() {
        return Collections.unmodifiableList(STATIONS);
    }
}
