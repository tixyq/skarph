package android.media;
public class SoundPool {
    public SoundPool(int maxStreams, int streamType, int srcQuality) { }
    public int load(android.content.res.AssetFileDescriptor afd, int priority) { return 0; }
    public boolean unload(int soundID) { return false; }
    public final int play(int soundID, float leftVolume, float rightVolume, int priority, int loop, float rate) { return 0; }
    public final void stop(int streamID) { }
    public final void pause(int streamID) { }
    public final void resume(int streamID) { }
    public final void setVolume(int streamID, float leftVolume, float rightVolume) { }
    public final void setRate(int streamID, float rate) { }
    public final void release() { }
}
