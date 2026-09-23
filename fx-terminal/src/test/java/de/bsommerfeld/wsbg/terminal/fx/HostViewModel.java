package de.bsommerfeld.wsbg.terminal.fx;

public final class HostViewModel implements ViewModel {

    private int leavesPicked;

    void leafPicked() {
        leavesPicked++;
    }

    int leavesPicked() {
        return leavesPicked;
    }
}
