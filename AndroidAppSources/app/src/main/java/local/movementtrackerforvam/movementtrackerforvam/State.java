package local.movementtrackerforvam.movementtrackerforvam;

import java.util.Dictionary;
import java.util.Objects;


public final class State implements Cloneable {

    //public boolean isTransmissionOn = false;

    public int accelerometerCalibration = -1;
    public int magnetometerCalibration = -1;
    public int gyroscopeCalibration = -1;

    public String clientsList = "";


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        State state = (State) o;
        return accelerometerCalibration == state.accelerometerCalibration &&
                magnetometerCalibration == state.magnetometerCalibration &&
                gyroscopeCalibration == state.gyroscopeCalibration &&
                Objects.equals(clientsList, state.clientsList);
    }

    @Override
    public int hashCode() {

        return Objects.hash(accelerometerCalibration, magnetometerCalibration, gyroscopeCalibration, clientsList);
    }

    public State getClone()
    {
        try {
            return (State)(this.clone());
        }catch(CloneNotSupportedException e) {
            return null;
        }
    }
}
