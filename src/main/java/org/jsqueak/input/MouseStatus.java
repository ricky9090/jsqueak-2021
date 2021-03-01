package org.jsqueak.input;

import org.jsqueak.core.SqueakVM;

import javax.swing.event.MouseInputAdapter;
import java.awt.event.MouseEvent;

public class MouseStatus extends MouseInputAdapter {
    private final SqueakVM fSqueakVM;

    public int fX, fY;
    public int fButtons;

    private final static int RED = 4;
    private final static int YELLOW = 2;
    private final static int BLUE = 1;

    public MouseStatus(SqueakVM squeakVM) {
        fSqueakVM = squeakVM;
    }

    private int mapButton(MouseEvent evt) {
        switch (evt.getButton()) {
            case MouseEvent.BUTTON1:
                if (evt.isControlDown()) {
                    return YELLOW;
                }
                if (evt.isAltDown()) {
                    return BLUE;
                }
                return RED;
            case MouseEvent.BUTTON2:
                return BLUE;        // middle (frame menu)
            case MouseEvent.BUTTON3:
                return YELLOW;  // right (pane menu)
            case MouseEvent.NOBUTTON:
                return 0;
        }
        throw new RuntimeException("unknown mouse button in event");
    }

    @Override
    public void mouseMoved(MouseEvent evt) {
        fX = evt.getX();
        fY = evt.getY();
        //wakeVM();
    }

    @Override
    public void mouseDragged(MouseEvent evt) {
        fX = evt.getX();
        fY = evt.getY();
        //wakeVM();
    }

    @Override
    public void mousePressed(MouseEvent evt) {
        fButtons |= mapButton(evt);
        //wakeVM();
    }

    @Override
    public void mouseReleased(MouseEvent evt) {
        fButtons &= ~mapButton(evt);
        //wakeVM();
    }

    /**
     * Mouse event no longer wake VM,
     * use InputNotifyThread to wake VM at fixed frequency
     */
    @Deprecated
    private void wakeVM() {
            synchronized (SqueakVM.class) {
                fSqueakVM.setScreenEvent(true);
                SqueakVM.class.notify();
            }

            synchronized (this) {
                try {
                    this.wait(0, 200);
                } catch (InterruptedException e) {

                }
            }

            synchronized (SqueakVM.class) {
                fSqueakVM.setScreenEvent(false);
            }
    }
}