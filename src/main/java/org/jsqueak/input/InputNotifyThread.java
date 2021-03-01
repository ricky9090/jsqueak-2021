package org.jsqueak.input;

import org.jsqueak.uilts.SqueakLogger;
import org.jsqueak.core.SqueakVM;

public class InputNotifyThread extends Thread {

    private final SqueakVM squeakVM;

    public InputNotifyThread(SqueakVM vm) {
        this.squeakVM = vm;
    }

    boolean running = true;

    /**
     * FIXME find better solution for Input Event
     */
    @Override
    public void run() {
        while (running) {
            synchronized (SqueakVM.class) {
                squeakVM.setScreenEvent(true);
                SqueakVM.class.notify();
            }

            try {
                Thread.sleep(0, 200);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            synchronized (SqueakVM.class) {
                squeakVM.setScreenEvent(false);
            }

            try {
                Thread.sleep(33);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        SqueakLogger.log_D("Quit InputNotifyThread");
    }

    public void quit() {
        this.running = false;
    }
}
