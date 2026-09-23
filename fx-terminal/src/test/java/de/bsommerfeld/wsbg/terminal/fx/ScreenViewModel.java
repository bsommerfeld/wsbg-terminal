package de.bsommerfeld.wsbg.terminal.fx;

public final class ScreenViewModel implements ViewModel {

    private int attached;
    private int detached;

    @Override
    public void onAttach() {
        attached++;
    }

    @Override
    public void onDetach() {
        detached++;
    }

    public int attached() {
        return attached;
    }

    public int detached() {
        return detached;
    }
}
