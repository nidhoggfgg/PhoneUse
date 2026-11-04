package ai.nidhoggfgg.phoneuse.voice;
import ai.nidhoggfgg.phoneuse.voice.IVoiceSession;

interface IVoiceHelper {
    IVoiceSession prepare(String packageName, int sampleRate, int byteCount);
}
