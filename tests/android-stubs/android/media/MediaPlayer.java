package android.media;
public class MediaPlayer {
    public interface OnPreparedListener { void onPrepared(MediaPlayer mp); }
    public void setDataSource(java.io.FileDescriptor fd, long offset, long length) throws java.io.IOException { }
    public void setLooping(boolean looping) { }
    public void setVolume(float l, float r) { }
    public void setOnPreparedListener(OnPreparedListener l) { }
    public void prepareAsync() { }
    public void start() { }
    public void stop() { }
    public void pause() { }
    public void release() { }
    public boolean isPlaying() { return false; }
}
