package local.movementtrackerforvam.movementtrackerforvam;

import java.util.LinkedList;

public class StateManager {


    public interface StateListener {
        public void stateListener(State newState, State oldState);
    }

    private static LinkedList<StateListener> listeners = new LinkedList<>();

    public static void addStateListener(StateListener listener,boolean triggerNow) {
        listeners.add(listener);
        if( triggerNow ) {
            State state = get();
            listener.stateListener(state, state);
        }
    }

    public static void removeStateListener(StateListener listener, boolean triggerFarewell) {
        listeners.remove(listener);
        if(triggerFarewell) listener.stateListener(null,get());
    }

    /* ****************************** */

    public interface StateUpdater {
        public State update(State state);
    }

    static public void update(StateUpdater updater) {
        State oldState = get();
        State newState = updater.update( oldState.getClone() );

        if( ! oldState.equals( newState ) ) {
            state = newState.getClone();

            for (StateListener l : listeners) {
                l.stateListener(newState, oldState);
            }
        }
    }

    /* ********************** */

    private static State state = new State();
    public static State get()
    {
        return state.getClone();
    }
}
