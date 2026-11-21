package com.slowbag.pulse.client.audio;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MergeClientSoundEvent;

public class PulseVoicechatPlugin implements VoicechatPlugin {

    @Override
    public String getPluginId() {
        return "pulse_music_player";
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
            event.mergeAudio(frame);
        }
    }
}
