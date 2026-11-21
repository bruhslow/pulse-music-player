package com.slowbag.pulse.client.audio;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MergeClientSoundEvent;

public class PulseVoicechatPlugin implements VoicechatPlugin {

    private static volatile VoicechatClientApi clientApi = null;

    @Override
    public String getPluginId() {
        return "pulse_music_player";
    }

    @Override
    public void initialize(VoicechatApi api) {
        if (api instanceof VoicechatClientApi client) {
            clientApi = client;
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MergeClientSoundEvent.class, this::onMergeClientSound);
    }

    private void onMergeClientSound(MergeClientSoundEvent event) {
        if (!SettingsManager.getInstance().isVoiceChatOutput()) {
            return;
        }
        short[] frame = SimpleVoiceChatBridge.getInstance().pollFrame();
        if (frame != null && frame.length > 0) {
            SimpleVoiceChatBridge.getInstance().markMergeHandled();
            event.mergeAudio(frame);
        }
    }

    public static VoicechatClientApi getClientApi() {
        return clientApi;
    }
}
