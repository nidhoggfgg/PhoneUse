package ai.nidhoggfgg.phoneuse.voice;

interface IVoiceSession {
    void append(in byte[] pcm);
    void press(float x, float y);
    // Keep the session alive and check for ACTION_UP. Playback and release run locally.
    boolean released();
    void close();
}
