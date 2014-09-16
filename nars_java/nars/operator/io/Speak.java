package nars.operator.io;

import nars.entity.AbstractTask;

/**
 * TODO wrap as operator
 * @author me
 */
public class Speak extends AbstractTask {
    public final Object signal;
    public final Class channel;

    public Speak(Class channel, Object signal) {
        super();
        this.channel = channel;
        this.signal = signal;
    }

    
    @Override
    public CharSequence getKey() {
        return channel.getSimpleName() + ": " + signal.hashCode();
    }
    
}
