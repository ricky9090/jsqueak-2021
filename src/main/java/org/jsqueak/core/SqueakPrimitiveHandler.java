/*
SqueakPrimitiveHandler.java
Copyright (c) 2008  Daniel H. H. Ingalls, Sun Microsystems, Inc.  All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
*/

package org.jsqueak.core;

import org.jsqueak.uilts.SqueakLogger;
import org.jsqueak.display.Screen;

import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/**
 * @author Daniel Ingalls
 * <p>
 * Implements the indexed primitives for the Squeak VM.
 */
public class SqueakPrimitiveHandler {
    private final PrimitiveFailedException PrimitiveFailed = new PrimitiveFailedException();

    private final SqueakVM vm;
    private final SqueakImage image;
    private final BitBlt bitbltTable;

    private final FileSystemPrimitives fileSystemPrimitives = new FileSystemPrimitives(this);

    private Screen theDisplay;
    private int[] displayBitmap;
    private int displayRaster;
    private byte[] displayBitmapInBytes;
    private int[] displayBitmapInInts;
    private int[] displayBitmapFromOrg;
    private int BWMask = 0;

    private boolean success = true;


    // Its purpose of the at-cache is to allow fast (bytecode) access to at/atput code
    // without having to check whether this object has overridden at, etc.
    private final int atCacheSize = 32; // must be power of 2
    private final int atCacheMask = atCacheSize - 1; //...so this is a mask

    private AtCacheInfo[] atCache;
    private AtCacheInfo[] atPutCache;
    private AtCacheInfo nonCachedInfo;

    SqueakPrimitiveHandler(SqueakVM theVM) {
        vm = theVM;
        image = vm.image;
        bitbltTable = new BitBlt(vm);
        initAtCache();
    }

    /**
     * A singleton instance of this class should be thrown to signal that a
     * primitive has failed.
     */
    private static class PrimitiveFailedException extends RuntimeException {
    }

    private static class AtCacheInfo {
        SqueakObject array;
        int size;
        int ivarOffset;
        boolean convertChars;
    }

    private void initAtCache() {
        atCache = new AtCacheInfo[atCacheSize];
        atPutCache = new AtCacheInfo[atCacheSize];
        nonCachedInfo = new AtCacheInfo();
        for (int i = 0; i < atCacheSize; i++) {
            atCache[i] = new AtCacheInfo();
            atPutCache[i] = new AtCacheInfo();
        }
    }

    /**
     * Clear at-cache pointers (prior to GC).
     */
    void clearAtCache() {
        for (int i = 0; i < atCacheSize; i++) {
            atCache[i].array = null;
            atPutCache[i].array = null;
        }
    }

    private AtCacheInfo makeCacheInfo(AtCacheInfo[] atOrPutCache, Object atOrPutSelector, SqueakObject array, boolean convertChars, boolean includeInstVars) {
        //Make up an info object and store it in the atCache or the atPutCache.
        //If it's not cacheable (not a non-super send of at: or at:put:)
        //then return the info in nonCachedInfo.
        //Note that info for objectAt (includeInstVars) will have
        //a zero ivarOffset, and a size that includes the extra instVars
        AtCacheInfo info;
        boolean cacheable = (vm.verifyAtSelector == atOrPutSelector) //is at or atPut
                && (vm.verifyAtClass == array.getSqClass())         //not a super send
                && (array.format == 3 && vm.isContext(array));        //not a context (size can change)
        if (cacheable) {
            info = atOrPutCache[array.hashCode() & atCacheMask];
        } else {
            info = nonCachedInfo;
        }
        info.array = array;
        info.convertChars = convertChars;
        if (includeInstVars) {
            info.size = Math.max(0, indexableSize(array)) + array.instSize();
            info.ivarOffset = 0;
        } else {
            info.size = indexableSize(array);
            info.ivarOffset = (array.format < 6) ? array.instSize() : 0;
        }
        return info;
    }

    // Quick Sends from inner Interpreter
    boolean quickSendOther(Object rcvr, int lobits) {
        // QuickSendOther returns true if it succeeds
        success = true;
        switch (lobits) {
            case 0x0:
                return popNandPushIfOK(2, primitiveAt(true, true, false)); // at:
            case 0x1:
                return popNandPushIfOK(3, primitiveAtPut(true, true, false)); // at:put:
            case 0x2:
                return popNandPushIfOK(1, primitiveSize()); // size
            case 0x3:
                return false; // next
            case 0x4:
                return false; // nextPut
            case 0x5:
                return false; // atEnd
            case 0x6:
                return pop2andDoBoolIfOK(primitiveEq(vm.stackValue(1), vm.stackValue(0))); // ==
            case 0x7:
                return popNandPushIfOK(1, vm.getClass(vm.top())); // class
            case 0x8:
                return popNandPushIfOK(2, primitiveBlockCopy()); // blockCopy:
            case 0x9:
                return primitiveBlockValue(0); // value
            case 0xa:
                return primitiveBlockValue(1); // value:
            case 0xb:
                return false; // do:
            case 0xc:
                return false; // new
            case 0xd:
                return false; // new:
            case 0xe:
                return false; // x
            case 0xf:
                return false; // y
            default:
                return false;
        }
    }

    private boolean primitiveEq(Object arg1, Object arg2) {
        // == must work for uninterned small ints
        if (vm.isSTInteger(arg1) && vm.isSTInteger(arg2)) {
            return ((Integer) arg1).intValue() == ((Integer) arg2).intValue();
        }
        return arg1 == arg2;
    }

    private Object primitiveBitAnd() {
        int rcvr = stackPos32BitValue(1);
        int arg = stackPos32BitValue(0);
        if (!success) {
            return vm.nilObj;
        }

        return pos32BitIntFor(rcvr & arg);
    }

    private Object primitiveBitOr() {
        int rcvr = stackPos32BitValue(1);
        int arg = stackPos32BitValue(0);
        if (!success) {
            return vm.nilObj;
        }

        return pos32BitIntFor(rcvr | arg);
    }

    private Object primitiveBitXor() {
        int rcvr = stackPos32BitValue(1);
        int arg = stackPos32BitValue(0);
        if (!success) {
            return vm.nilObj;
        }

        return pos32BitIntFor(rcvr ^ arg);
    }

    private Object primitiveBitShift() {
        int rcvr = stackPos32BitValue(1);
        int arg = stackInteger(0);
        if (!success) {
            return vm.nilObj;
        }

        return pos32BitIntFor(SqueakVM.safeShift(rcvr, arg));
    }

    private int doQuo(int rcvr, int arg) {
        if (arg == 0) {
            this.success = false;
            return 0;
        }

        if (rcvr > 0) {
            if (arg > 0) {
                return rcvr / arg;
            } else {
                return 0 - (rcvr / (0 - arg));
            }
        } else {
            if (arg > 0) {
                return 0 - ((0 - rcvr) / arg);
            } else {
                return (0 - rcvr) / (0 - arg);
            }
        }
    }

    boolean doPrimitive(int index, int argCount) {
        //SqueakLogger.log_D("doPrimitive: " + index + ", time: " + System.currentTimeMillis());
        success = true;
        switch (index) {
            // 0..127
            case 1:
                return popNandPushIntIfOK(2, stackInteger(1) + stackInteger(0));  // Integer.add
            case 2:
                return popNandPushIntIfOK(2, stackInteger(1) - stackInteger(0));  // Integer.subtract
            case 3:
                return pop2andDoBoolIfOK(stackInteger(1) < stackInteger(0));  // Integer.less
            case 4:
                return pop2andDoBoolIfOK(stackInteger(1) > stackInteger(0));  // Integer.greater
            case 5:
                return pop2andDoBoolIfOK(stackInteger(1) <= stackInteger(0));  // Integer.leq
            case 6:
                return pop2andDoBoolIfOK(stackInteger(1) >= stackInteger(0));  // Integer.geq
            case 7:
                return pop2andDoBoolIfOK(stackInteger(1) == stackInteger(0));  // Integer.equal
            case 8:
                return pop2andDoBoolIfOK(stackInteger(1) != stackInteger(0));  // Integer.notequal
            case 9:
                return popNandPushIntIfOK(2, SqueakVM.safeMultiply(stackInteger(1), stackInteger(0)));  // Integer.multiply *
            case 10:
                return popNandPushIntIfOK(2, SqueakVM.quickDivide(stackInteger(1), stackInteger(0)));  // Integer.divide /  (fails unless exact exact)
            case 11:
                return false; //popNandPushIntIfOK(2,doMod(stackInteger(1),stackInteger(0)));  // Integer.mod \\
            case 12:
                return popNandPushIntIfOK(2, SqueakVM.div(stackInteger(1), stackInteger(0)));  // Integer.div //
            case 13:
                return popNandPushIntIfOK(2, doQuo(stackInteger(1), stackInteger(0)));  // Integer.quo
            case 14:
                return popNandPushIfOK(2, primitiveBitAnd());  // SmallInt.bitAnd
            case 15:
                return popNandPushIfOK(2, primitiveBitOr());  // SmallInt.bitOr
            case 16:
                return popNandPushIfOK(2, primitiveBitXor());  // SmallInt.bitXor
            case 17:
                return popNandPushIfOK(2, primitiveBitShift());  // SmallInt.bitShift
            case 18:
                return primitiveMakePoint();
            case 19:
                return false;  // Guard primitive for simulation -- *must* fail
            case 40:
                return primitiveAsFloat();
            case 41:
                return popNandPushFloatIfOK(2, stackFloat(1) + stackFloat(0));  // Float +        // +
            case 42:
                return popNandPushFloatIfOK(2, stackFloat(1) - stackFloat(0));  // Float -
            case 43:
                return pop2andDoBoolIfOK(stackFloat(1) < stackFloat(0));  // Float <
            case 44:
                return pop2andDoBoolIfOK(stackFloat(1) > stackFloat(0));  // Float >
            case 45:
                return pop2andDoBoolIfOK(stackFloat(1) <= stackFloat(0));  // Float <=
            case 46:
                return pop2andDoBoolIfOK(stackFloat(1) >= stackFloat(0));  // Float >=
            case 47:
                return pop2andDoBoolIfOK(stackFloat(1) == stackFloat(0));  // Float =
            case 48:
                return pop2andDoBoolIfOK(stackFloat(1) != stackFloat(0));  // Float !=
            case 49:
                return popNandPushFloatIfOK(2, stackFloat(1) * stackFloat(0));  // Float.mul
            case 50:
                return popNandPushFloatIfOK(2, safeFDiv(stackFloat(1), stackFloat(0)));  // Float.div
            case 51:
                return primitiveTruncate();
            case 58:
                return popNandPushFloatIfOK(1, StrictMath.log(stackFloat(0)));  // Float.ln
            case 60:
                return popNandPushIfOK(2, primitiveAt(false, false, false)); // basicAt:
            case 61:
                return popNandPushIfOK(3, primitiveAtPut(false, false, false)); // basicAt:put:
            case 62:
                return popNandPushIfOK(1, primitiveSize()); // size
            case 63:
                return popNandPushIfOK(2, primitiveAt(false, true, false)); // basicAt:
            case 64:
                return popNandPushIfOK(3, primitiveAtPut(false, true, false)); // basicAt:put:
            case 68:
                return popNandPushIfOK(2, primitiveAt(false, false, true)); // Method.objectAt:
            case 69:
                return popNandPushIfOK(3, primitiveAtPut(false, false, true)); // Method.objectAt:put:
            case 70:
                return popNandPushIfOK(1, vm.instantiateClass(stackNonInteger(0), 0)); // Class.new
            case 71:
                return popNandPushIfOK(2, primitiveNewWithSize()); // Class.new
            case 72:
                return popNandPushIfOK(2, primitiveArrayBecome(false));
            case 73:
                return popNandPushIfOK(2, primitiveAt(false, false, true)); // instVarAt:
            case 74:
                return popNandPushIfOK(3, primitiveAtPut(false, false, true)); // instVarAt:put:
            case 75:
                return popNandPushIfOK(1, primitiveHash()); // Class.identityHash
            case 77:
                return popNandPushIfOK(1, primitiveSomeInstance(stackNonInteger(0))); // Class.someInstance
            case 78:
                return popNandPushIfOK(1, primitiveNextInstance(stackNonInteger(0))); // Class.someInstance
            case 79:
                return popNandPushIfOK(3, primitiveNewMethod()); // Compiledmethod.new
            case 80:
                return popNandPushIfOK(2, primitiveBlockCopy()); // Context.blockCopy:
            case 81:
                return primitiveBlockValue(argCount); // BlockContext.value
            case 83:
                return vm.primitivePerform(argCount); // rcvr.perform:(with:)*
            case 84:
                return vm.primitivePerformWithArgs(vm.getClass(vm.stackValue(2))); // rcvr.perform:withArguments:
            case 85:
                return semaphoreSignal(); // Semaphore.wait
            case 86:
                return semaphoreWait(); // Semaphore.wait
            case 87:
                return processResume(); // Process.resume
            case 88:
                return processSuspend(); // Process.suspend
            case 89:
                return vm.clearMethodCache();  // selective
            case 90:
                return popNandPushIfOK(1, primitiveMousePoint()); // mousePoint
            case 96:
                if (argCount == 0) return primitiveCopyBits((SqueakObject) vm.top(), 0);
                else return primitiveCopyBits((SqueakObject) vm.stackValue(1), 1);
            case 100:
                return vm.primitivePerformInSuperclass((SqueakObject) vm.top()); // rcvr.perform:withArguments:InSuperclass
            case 101:
                return beCursor(argCount); // Cursor.beCursor
            case 102:
                return beDisplay(argCount); // DisplayScreen.beDisplay
            case 103:
                return primitiveScanCharacters();
            case 105:
                return popNandPushIfOK(5, primitiveStringReplace()); // string and array replace
            case 106:
                //return popNandPushIfOK(1, makePointWithXandY(SqueakVM.smallFromInt(640), SqueakVM.smallFromInt(480))); // actualScreenSize
                return primitiveScreenSize();
            case 107:
                return popNandPushIfOK(1, primitiveMouseButtons()); // Sensor mouseButtons
            case 108:
                return popNandPushIfOK(1, primitiveKbdNext()); // Sensor kbdNext
            case 109:
                return popNandPushIfOK(1, primitiveKbdPeek()); // Sensor kbdPeek
            case 110:
                return popNandPushIfOK(2, (vm.stackValue(1) == vm.stackValue(0)) ? vm.trueObj : vm.falseObj); // ==
            case 112:
                return popNandPushIfOK(1, SqueakVM.smallFromInt(image.spaceLeft())); // bytesLeft
            case 113: {
                System.exit(0);
                return true;
            }
            case 116:
                return vm.flushMethodCacheForMethod((SqueakObject) vm.top());
            case 119:
                return vm.flushMethodCacheForSelector((SqueakObject) vm.top());
            case 121:
                return popNandPushIfOK(1, makeStString("Macintosh HD:Users:danielingalls:Recent Squeaks:Old 3.3:mini.image")); //imageName
            case 122: {
                BWMask = ~BWMask;
                return true;
            }
            case 124:
                return popNandPushIfOK(2, registerSemaphore(Squeak.splOb_TheLowSpaceSemaphore));
            case 125:
                return popNandPushIfOK(2, setLowSpaceThreshold());
            case 128:
                return popNandPushIfOK(2, primitiveArrayBecome(true));
            case 129:
                return popNandPushIfOK(1, image.specialObjectsArray);
            case 130:
                return popNandPushIfOK(1, SqueakVM.smallFromInt(image.fullGC())); // GC
            case 131:
                return popNandPushIfOK(1, SqueakVM.smallFromInt(image.partialGC())); // GCmost
            case 134:
                return popNandPushIfOK(2, registerSemaphore(Squeak.splOb_TheInterruptSemaphore));
            case 135:
                return popNandPushIfOK(1, millisecondClockValue());
            case 136:
                return popNandPushIfOK(3, primitiveSignalAtMilliseconds()); //Delay signal:atMs:());
            case 137:
                return popNandPushIfOK(1, primSeconds()); //Seconds since Jan 1, 1901
            case 138:
                return popNandPushIfOK(1, primitiveSomeObject()); // Class.someInstance
            case 139:
                return popNandPushIfOK(1, primitiveNextObject(stackNonInteger(0))); // Class.someInstance
            case 142:
                return popNandPushIfOK(1, makeStString("Macintosh HD:Users:danielingalls:Recent Squeaks:Squeak VMs etc.:")); //vmPath
            case 148:
                return popNandPushIfOK(1, ((SqueakObject) vm.top()).cloneIn(image)); //imageName
            case 149:
                return popNandPushIfOK(2, vm.nilObj); //getAttribute
            case 161:
                return popNandPushIfOK(1, charFromInt(58)); //path delimiter
            case 230:
                return primitiveYield(argCount); //yield for 10ms
            case 233:
                return primitiveSetFullScreen();
            case 699:
                primitiveDebug();
                break;
            default:
                return false;
        }
        return success;
    }

    /**
     * snapshotPrimitive
     * "Primitive. Write the current state of the object memory on a file in the
     * same format as the Smalltalk-80 release. The file can later be resumed,
     * returning you to this exact state. Return normally after writing the file.
     * Essential. See Object documentation whatIsAPrimitive."
     * <p>
     * <primitive: 97>
     * ^nil "indicates error writing image file"
     */
    private void primitiveSnapshot() {
        System.out.println("Saving the image");
        try {
            vm.image.save(new File("/tmp/image.gz"));
        } catch (IOException e) {
            e.printStackTrace();
            this.success = false;
        }
    }

    /**
     * Primitive 121
     * "When called with a single string argument, record the string
     * as the current image file name. When called with zero
     * arguments, return a string containing the current image file
     * name."
     */
    private Object primitiveImageFileName(int argCount) {
        if (argCount == 0) {
            return makeStString(vm.image.imageFile().getAbsolutePath());
        }

        if (argCount == 1) {
            this.success = false;
        }
        new Exception("Cannot set the image name yet, argument is '" + stackNonInteger(0) + "'").printStackTrace();

        return "";
    }

    /**
     * SystemDictionary>>vmPath.
     * Primitive 142.
     * <p>
     * primVmPath
     * "Answer the path for the directory containing the Smalltalk virtual machine.
     * Return the empty string if this primitive is not implemented."
     * "Smalltalk vmPath"
     * <p>
     * <primitive: 142>
     * ^ ''
     */
    private SqueakObject primitiveVmPath() {
        return makeStString(System.getProperty("user.dir"));
    }

    /*private boolean pop2andDoBool(boolean bool) {
        vm.success = true; // FIXME: Why have a side effect here?
        success = true;
        return vm.pushBoolAndPeek(bool);
    }*/


    /*private void popNandPush(int nToPop, Object returnValue) {
        if (returnValue == null)
            new Exception("NULL in popNandPush()").printStackTrace(); // FIXME: Did I break this by not checking for a null return value?

        vm.popNandPush(nToPop, returnValue);
    }*/

    /*private void popNandPushInt(int nToPop, int returnValue) {
        Integer value = SqueakVM.smallFromInt(returnValue);
        if (value == null)
            this.success = false;

        popNandPush(nToPop, value);
    }*/

    /*private void popNandPushFloat(int nToPop, double returnValue) {

        popNandPush(nToPop, makeFloat(returnValue));
    }*/

    int stackInteger(int nDeep) {
        return checkSmallInt(vm.stackValue(nDeep));
    }

    /**
     * If maybeSmall is a small integer, return its value, fail otherwise.
     */
    private int checkSmallInt(Object maybeSmall) {
        if (vm.isSTInteger(maybeSmall)) {
            return (Integer) maybeSmall;
        }

        this.success = false;
        return 0;
    }

    private double stackFloat(int nDeep) {
        return checkFloat(vm.stackValue(nDeep));
    }

    /**
     * If maybeFloat is a Squeak Float return its value, fail otherwise
     */
    private double checkFloat(Object maybeFloat) {
        if (vm.getClass(maybeFloat) == vm.specialObjects[Squeak.splOb_ClassFloat]) {
            return ((SqueakObject) maybeFloat).getFloatBits();
        }

        // FIXME is it ok to treat integer as float ? see SqueakJS <checkFloat> at vm.primitives.js
        if (vm.isSTInteger(maybeFloat)) {
            return ((Integer) maybeFloat).doubleValue();
        }

        this.success = false;
        return 0.0d;
    }

    private double safeFDiv(double dividend, double divisor) {
        if (divisor == 0.0d) {
            this.success = false;
            return 1.0d;
        }

        return dividend / divisor;
    }

    /**
     * Fail if maybeSmall is not a SmallInteger
     *
     * @param maybeSmall
     * @return
     */
    private SqueakObject checkNonSmallInt(Object maybeSmall) {
        // returns a SqObj and sets success

        if (vm.isSTInteger(maybeSmall)) {
            this.success = false;
            return vm.nilObj;
        }

        return (SqueakObject) maybeSmall;
    }

    int stackPos32BitValue(int nDeep) {
        Object stackVal = vm.stackValue(nDeep);
        if (vm.isSTInteger(stackVal)) {
            int value = (Integer) stackVal;
            if (value >= 0) {
                return value;
            }

            this.success = false;
            return 0;
        }

        if (!isA(stackVal, Squeak.splOb_ClassLargePositiveInteger)) {
            this.success = false;
            return 0;
        }


        byte[] bytes = (byte[]) ((SqueakObject) stackVal).bits;
        int value = 0;
        for (int i = 0; i < 4; i++) {
            value = value + ((bytes[i] & 255) << (8 * i));
        }
        return value;
    }

    Object pos32BitIntFor(long pos32Val) {
        if (pos32Val < Integer.MIN_VALUE ||
                pos32Val > Integer.MAX_VALUE) {
            new Exception("long to int overflow").printStackTrace();
            this.success = false;
        }

        return pos32BitIntFor((int) pos32Val);
    }

    Object pos32BitIntFor(int pos32Val) {
        // Return the 32-bit quantity as a positive 32-bit integer
        if (pos32Val >= 0) {
            Object smallInt = SqueakVM.smallFromInt(pos32Val);
            if (smallInt != null) {
                return smallInt;
            }
        }
        SqueakObject lgIntClass = (SqueakObject) vm.specialObjects[Squeak.splOb_ClassLargePositiveInteger];
        SqueakObject lgIntObj = vm.instantiateClass(lgIntClass, 4);
        byte[] bytes = (byte[]) lgIntObj.bits;
        for (int i = 0; i < 4; i++) {
            bytes[i] = (byte) ((pos32Val >>> (8 * i)) & 255);
        }
        return lgIntObj;
    }

    SqueakObject stackNonInteger(int nDeep) {
        return checkNonSmallInt(vm.stackValue(nDeep));
    }

    SqueakObject squeakArray(Object[] javaArray) {
        SqueakObject array = vm.instantiateClass(Squeak.splOb_ClassArray, javaArray.length);
        for (int index = 0; index < javaArray.length; index++) {
            array.setPointer(index, javaArray[index]);
        }

        return array;
    }

    Object squeakSeconds(long millis) {
        int secs = (int) (millis / 1000); //milliseconds -> seconds
        secs += (69 * 365 + 17) * 24 * 3600; //Adjust from 1901 to 1970

        return pos32BitIntFor(secs);
    }


    SqueakObject squeakNil() {
        return vm.nilObj;
    }

    SqueakObject squeakBool(boolean bool) {
        return bool ? vm.trueObj : vm.falseObj;
    }

    /**
     * Note: this method does not check to see that the passed
     * object is an instance of Boolean.
     *
     * @return true iff object is the special Squeak true object
     */
    boolean javaBool(SqueakObject object) {
        return object == vm.trueObj;
    }

    private boolean primitiveAsFloat() {
        int intValue = stackInteger(0);
        if (!success) {
            return false;
        }
        vm.popNandPush(1, makeFloat(intValue));
        return true;
    }

    private SqueakObject makeFloat(double value) {
        SqueakObject floatClass = (SqueakObject) vm.specialObjects[Squeak.splOb_ClassFloat];
        SqueakObject newFloat = vm.instantiateClass(floatClass, -1);
        newFloat.setFloatBits(value);
        return newFloat;
    }

    boolean primitiveMakePoint() {
        Object x = vm.stackValue(1);
        Object y = vm.stackValue(0);
        if (!vm.isSTInteger(x) || !vm.isSTInteger(y)) {
            success = false;
            return false;
        }

        vm.popNandPush(2, makePointWithXandY(x, y));
        return true;
    }

    private SqueakObject makePointWithXandY(Object x, Object y) {
        //SqueakLogger.log_D("make point x: " + x + ",  y: " + y);
        SqueakObject pointClass = (SqueakObject) vm.specialObjects[Squeak.splOb_ClassPoint];
        SqueakObject newPoint = vm.instantiateClass(pointClass, 0);
        newPoint.setPointer(Squeak.Point_x, x);
        newPoint.setPointer(Squeak.Point_y, y);
        return newPoint;
    }

    private SqueakObject primitiveNewWithSize() {
        int size = stackPos32BitValue(0);
        if (!success) {
            return vm.nilObj;
        }

        return vm.instantiateClass(((SqueakObject) vm.stackValue(1)), size);
    }

    private SqueakObject primitiveNewMethod() {
        Object headerInt = vm.top();
        int byteCount = stackInteger(1);
        int methodHeader = checkSmallInt(headerInt);
        if (!success) {
            return vm.nilObj;
        }
        int litCount = (methodHeader >> 9) & 0xFF;
        SqueakObject method = vm.instantiateClass(((SqueakObject) vm.stackValue(2)), byteCount);
        Object[] pointers = new Object[litCount + 1];
        Arrays.fill(pointers, vm.nilObj);
        pointers[0] = headerInt;
        method.methodAddPointers(pointers);
        return method;
    }


    //String and Array Primitives
    // FIXME: makeStString() but squeakBool() ? Pick one!
    SqueakObject makeStString(String javaString) {
        byte[] byteString = javaString.getBytes();
        SqueakObject stString = vm.instantiateClass((SqueakObject) vm.specialObjects[Squeak.splOb_ClassString], javaString.length());
        System.arraycopy(byteString, 0, stString.bits, 0, byteString.length);
        return stString;
    }

    /**
     * Returns size Integer (but may set success false)
     */
    private Object primitiveSize() {
        Object rcvr = vm.top();
        int size = indexableSize(rcvr);
        if (size == -1) {
            //not indexable
            this.success = false;
        }

        return pos32BitIntFor(size);
    }

    private Object primitiveAt(boolean cameFromAtBytecode, boolean convertChars, boolean includeInstVars) {
        //Returns result of at: or sets success false
        SqueakObject array = stackNonInteger(1);
        int index = stackPos32BitValue(0); //note non-int returns zero
        if (!success) {
            return array;
        }

        AtCacheInfo info;
        if (cameFromAtBytecode) {
            // fast entry checks cache
            info = atCache[array.hashCode() & atCacheMask];
            if (info.array != array) {
                this.success = false;
                return array;
            }

        } else {
            // slow entry installs in cache if appropriate
            if (array.format == 6 && isA(array, Squeak.splOb_ClassFloat)) {
                // hack to make Float hash work
                long floatBits = Double.doubleToRawLongBits(array.getFloatBits());
                if (index == 1) {
                    return pos32BitIntFor((int) (floatBits >>> 32));
                }
                if (index == 2) {
                    return pos32BitIntFor((int) (floatBits & 0xFFFFFFFF));
                }

                this.success = false;
                return array;
            }
            info = makeCacheInfo(atCache, vm.specialSelectors[32], array, convertChars, includeInstVars);
        }
        if (index < 1 || index > info.size) {
            this.success = false;
            return array;
        }


        if (includeInstVars) { //pointers...   instVarAt and objectAt
            return array.pointers[index - 1];
        }
        if (array.format < 6) {  //pointers...   normal at:
            return array.pointers[index - 1 + info.ivarOffset];
        }
        if (array.format < 8) {  // words...
            int value = ((int[]) array.bits)[index - 1];
            return pos32BitIntFor(value);
        }
        if (array.format < 12) { // bytes...
            int value = (((byte[]) array.bits)[index - 1]) & 0xFF;
            if (info.convertChars) {
                return charFromInt(value);
            } else {
                return SqueakVM.smallFromInt(value);
            }
        }
        // methods (format>=12) must simulate Squeak's method indexing
        int offset = array.pointersSize() * 4;
        if (index - 1 - offset < 0) { //reading lits as bytes
            this.success = false;
            return array;
        }

        return SqueakVM.smallFromInt((((byte[]) array.bits)[index - 1 - offset]) & 0xFF);
    }

    SqueakObject charFromInt(int ascii) {
        SqueakObject charTable = (SqueakObject) vm.specialObjects[Squeak.splOb_CharacterTable];
        return charTable.getPointerNI(ascii);
    }

    /**
     * @return result of at:put:
     */
    private Object primitiveAtPut(boolean cameFromAtBytecode, boolean convertChars, boolean includeInstVars) {
        SqueakObject array = stackNonInteger(2);
        int index = stackPos32BitValue(1); //note non-int returns zero
        if (!success) {
            return array;
        }

        AtCacheInfo info;
        if (cameFromAtBytecode) {
            // fast entry checks cache
            info = atPutCache[array.hashCode() & atCacheMask];
            if (info.array != array) {
                this.success = false;
                return array;
            }
        } else {
            // slow entry installs in cache if appropriate
            info = makeCacheInfo(atPutCache, vm.specialSelectors[34], array, convertChars, includeInstVars);
        }
        if (index < 1 || index > info.size) {
            this.success = false;
            return array;
        }

        Object objToPut = vm.stackValue(0);
        if (includeInstVars) {
            // pointers...   instVarAtPut and objectAtPut
            array.pointers[index - 1] = objToPut; //eg, objectAt:
            return objToPut;
        }
        if (array.format < 6) {
            // pointers...   normal atPut
            array.pointers[index - 1 + info.ivarOffset] = objToPut;
            return objToPut;
        }
        int intToPut;
        if (array.format < 8) {
            // words...
            intToPut = stackPos32BitValue(0);
            if (!success) {
                return objToPut;
            }

            ((int[]) array.bits)[index - 1] = intToPut;
            return objToPut;
        }
        // bytes...
        if (info.convertChars) {
            // put a character...
            if (vm.isSTInteger(objToPut)) {
                this.success = false;
                return objToPut;
            }

            SqueakObject sqObjToPut = (SqueakObject) objToPut;
            if ((sqObjToPut.sqClass != vm.specialObjects[Squeak.splOb_ClassCharacter])) {
                this.success = false;
                return objToPut;
            }

            Object asciiToPut = sqObjToPut.getPointer(0);
            if (!(vm.isSTInteger(asciiToPut))) {
                this.success = false;
                return objToPut;
            }

            intToPut = (Integer) asciiToPut;
        } else {
            // put a byte...
            if (!(vm.isSTInteger(objToPut))) {
                this.success = false;
                return objToPut;
            }

            intToPut = (Integer) objToPut;
        }
        if (intToPut < 0 || intToPut > 255) {
            this.success = false;
            return objToPut;
        }

        if (array.format < 8) {
            // bytes...
            ((byte[]) array.bits)[index - 1] = (byte) intToPut;
            return objToPut;
        }
        // methods (format>=12) must simulate Squeak's method indexing
        int offset = array.pointersSize() * 4;
        if (index - 1 - offset < 0) {
            this.success = false;   //writing lits as bytes
            return array;
        }

        ((byte[]) array.bits)[index - 1 - offset] = (byte) intToPut;
        return objToPut;
    }

    // FIXME: is this the same as SqueakObject.instSize() ?
    private int indexableSize(Object obj) {
        if (vm.isSTInteger(obj)) {
            return -1; // -1 means not indexable
        }
        SqueakObject sqObj = (SqueakObject) obj;
        short fmt = sqObj.format;
        if (fmt < 2) {
            return -1; //not indexable
        }
        if (fmt == 3 && vm.isContext(sqObj)) {
            return sqObj.getPointerI(Squeak.Context_stackPointer).intValue();
        }
        if (fmt < 6) {
            return sqObj.pointersSize() - sqObj.instSize(); // pointers
        }
        if (fmt < 12) {
            return sqObj.bitsSize(); // words or bytes
        }
        return sqObj.bitsSize() + (4 * sqObj.pointersSize());  // methods
    }

    private SqueakObject primitiveStringReplace() {
        SqueakObject dst = (SqueakObject) vm.stackValue(4);
        int dstPos = stackInteger(3) - 1;
        int count = stackInteger(2) - dstPos;
        //  if (count<=0) {success= false; return dst; } //fail for compat, later succeed
        SqueakObject src = (SqueakObject) vm.stackValue(1);
        int srcPos = stackInteger(0) - 1;
        if (!success) {
            return vm.nilObj; //some integer not right
        }
        short srcFmt = src.format;
        short dstFmt = dst.format;
        if (dstFmt < 8) {
            if (dstFmt != srcFmt) {
                //incompatible formats
                this.success = false;
                return dst;
            } else if ((dstFmt & 0xC) != (srcFmt & 0xC)) {
                //incompatible formats
                this.success = false;
                return dst;
            }
        }
        if (srcFmt < 4) {
            //pointer type objects
            int totalLength = src.pointersSize();
            int srcInstSize = src.instSize();
            srcPos += srcInstSize;
            if ((srcPos < 0) || (srcPos + count) > totalLength) {
                //would go out of bounds
                this.success = false;
                return vm.nilObj;
            }

            totalLength = dst.pointersSize();
            int dstInstSize = dst.instSize();
            dstPos += dstInstSize;
            if ((dstPos < 0) || (dstPos + count) > totalLength) {
                //would go out of bounds
                this.success = false;
                return vm.nilObj;
            }

            System.arraycopy(src.pointers, srcPos, dst.pointers, dstPos, count);
            return dst;
        } else {
            //bits type objects
            int totalLength = src.bitsSize();
            if ((srcPos < 0) || (srcPos + count) > totalLength) {
                //would go out of bounds
                this.success = false;
                return vm.nilObj;
            }
            totalLength = dst.bitsSize();
            if ((dstPos < 0) || (dstPos + count) > totalLength) {
                //would go out of bounds
                this.success = false;
                return vm.nilObj;
            }
            System.arraycopy(src.bits, srcPos, dst.bits, dstPos, count);
            return dst;
        }
    }

    //Not yet implemented...
    private boolean primitiveNext() {
        // PrimitiveNext should succeed only if the stream's array is in the atCache.
        // Otherwise failure will lead to proper message lookup of at: and
        // subsequent installation in the cache if appropriate."
        SqueakObject stream = stackNonInteger(0);
        if (!success) {
            return false;
        }
        Object[] streamBody = stream.pointers;
        if (streamBody == null || streamBody.length < (Squeak.Stream_limit + 1)) {
            return false;
        }
        Object array = streamBody[Squeak.Stream_array];
        if (vm.isSTInteger(array)) {
            return false;
        }
        int index = checkSmallInt(streamBody[Squeak.Stream_position]);
        int limit = checkSmallInt(streamBody[Squeak.Stream_limit]);
        int arraySize = indexableSize(array);
        if (index >= limit) {
            return false;
        }
        //  (index < limit and: [(atCache at: atIx+AtCacheOop) = array])
        //      ifFalse: [^ self primitiveFail].
        //
        //  "OK -- its not at end, and the array is in the cache"
        //  index _ index + 1.
        //  result _ self commonVariable: array at: index cacheIndex: atIx.
        //  "Above may cause GC, so can't use stream, array etc. below it"
        //  successFlag ifTrue:
        //      [stream _ self stackTop.
        //      self storeInteger: StreamIndexIndex ofObject: stream withValue: index.
        //      ^ self pop: 1 thenPush: result].
        return false;
    }

    private SqueakObject primitiveBlockCopy() {
        Object rcvr = vm.stackValue(1);
        if (vm.isSTInteger(rcvr)) {
            this.success = false;
        }

        Object sqArgCount = vm.top();
        if (!(vm.isSTInteger(sqArgCount))) {
            this.success = false;
        }

        SqueakObject homeCtxt = (SqueakObject) rcvr;
        if (!vm.isContext(homeCtxt)) {
            this.success = false;
        }
        if (!success) {
            return vm.nilObj;
        }

        if (vm.isSTInteger(homeCtxt.getPointer(Squeak.Context_method))) {
            // ctxt is itself a block; get the context for its enclosing method
            homeCtxt = homeCtxt.getPointerNI(Squeak.BlockContext_home);
        }
        int blockSize = homeCtxt.pointersSize() - homeCtxt.instSize(); //can use a const for instSize
        SqueakObject newBlock = vm.instantiateClass(((SqueakObject) vm.specialObjects[Squeak.splOb_ClassBlockContext]), blockSize);
        Integer initialPC = vm.encodeSqueakPC(vm.pc + 2, vm.method); //*** check this...
        newBlock.setPointer(Squeak.BlockContext_initialIP, initialPC);
        newBlock.setPointer(Squeak.Context_instructionPointer, initialPC);// claim not needed; value will set it
        newBlock.setPointer(Squeak.Context_stackPointer, SqueakVM.smallFromInt(0));
        newBlock.setPointer(Squeak.BlockContext_argumentCount, sqArgCount);
        newBlock.setPointer(Squeak.BlockContext_home, homeCtxt);
        newBlock.setPointer(Squeak.Context_sender, vm.nilObj);
        return newBlock;
    }

    private boolean primitiveBlockValue(int argCount) {
        Object rcvr = vm.stackValue(argCount);
        if (!isA(rcvr, Squeak.splOb_ClassBlockContext)) {
            return false;
        }
        SqueakObject block = (SqueakObject) rcvr;
        Object blockArgCount = block.getPointer(Squeak.BlockContext_argumentCount);
        if (!vm.isSTInteger(blockArgCount)) {
            return false;
        }
        if ((((Integer) blockArgCount).intValue() != argCount)) {
            return false;
        }
        if (block.getPointer(Squeak.BlockContext_caller) != vm.nilObj) {
            return false;
        }
        System.arraycopy((Object) vm.activeContext.pointers, vm.sp - argCount + 1, (Object) block.pointers, Squeak.Context_tempFrameStart, argCount);
        Integer initialIP = block.getPointerI(Squeak.BlockContext_initialIP);
        block.setPointer(Squeak.Context_instructionPointer, initialIP);
        block.setPointer(Squeak.Context_stackPointer, new Integer(argCount));
        block.setPointer(Squeak.BlockContext_caller, vm.activeContext);
        vm.popN(argCount + 1);
        vm.newActiveContext(block);
        return true;
    }

    private Object primitiveHash() {
        Object rcvr = vm.top();
        if (vm.isSTInteger(rcvr)) {
            this.success = false;
            return vm.nilObj;
        }

        return new Integer(((SqueakObject) rcvr).hash);
    }

    private Object setLowSpaceThreshold() {
        int nBytes = stackInteger(0);
        if (success) {
            vm.lowSpaceThreshold = nBytes;
        }
        return vm.stackValue(1);
    }

    // Scheduler Primitives
    private SqueakObject getScheduler() {
        SqueakObject assn = (SqueakObject) vm.specialObjects[Squeak.splOb_SchedulerAssociation];
        return assn.getPointerNI(Squeak.Assn_value);
    }

    private boolean processResume() {
        SqueakObject process = (SqueakObject) vm.top();
        resume(process);
        return true;
    }

    private boolean processSuspend() {
        SqueakObject activeProc = getScheduler().getPointerNI(Squeak.ProcSched_activeProcess);
        if (vm.top() != activeProc) {
            return false;
        }

        vm.popNandPush(1, vm.nilObj);
        transferTo(pickTopProcess());
        return true;
    }

    private boolean isA(Object obj, int knownClass) {
        Object itsClass = vm.getClass(obj);
        return itsClass == vm.specialObjects[knownClass];
    }

    private boolean isKindOf(Object obj, int knownClass) {
        Object classOrSuper = vm.getClass(obj);
        Object theClass = vm.specialObjects[knownClass];
        while (classOrSuper != vm.nilObj) {
            if (classOrSuper == theClass) {
                return true;
            }
            classOrSuper = ((SqueakObject) classOrSuper).pointers[Squeak.Class_superclass];
        }
        return false;
    }

    private boolean semaphoreWait() {
        SqueakObject sema = (SqueakObject) vm.top();
        if (!isA(sema, Squeak.splOb_ClassSemaphore)) {
            return false;
        }

        int excessSignals = sema.getPointerI(Squeak.Semaphore_excessSignals).intValue();
        if (excessSignals > 0) {
            sema.setPointer(Squeak.Semaphore_excessSignals, SqueakVM.smallFromInt(excessSignals - 1));
        } else {
            SqueakObject activeProc = getScheduler().getPointerNI(Squeak.ProcSched_activeProcess);
            linkProcessToList(activeProc, sema);
            transferTo(pickTopProcess());
        }
        return true;
    }

    private boolean semaphoreSignal() {
        SqueakObject sema = (SqueakObject) vm.top();
        if (!isA(sema, Squeak.splOb_ClassSemaphore)) {
            return false;
        }

        synchronousSignal(sema);
        return true;
    }

    void synchronousSignal(SqueakObject sema) {
        if (isEmptyList(sema)) {
            //no process is waiting on this semaphore"
            int excessSignals = sema.getPointerI(Squeak.Semaphore_excessSignals).intValue();
            sema.setPointer(Squeak.Semaphore_excessSignals, SqueakVM.smallFromInt(excessSignals + 1));
        } else {
            resume(removeFirstLinkOfList(sema));
        }
        return;
    }

    private void resume(SqueakObject newProc) {
        SqueakObject activeProc = getScheduler().getPointerNI(Squeak.ProcSched_activeProcess);
        int activePriority = activeProc.getPointerI(Squeak.Proc_priority).intValue();
        int newPriority = newProc.getPointerI(Squeak.Proc_priority).intValue();
        if (newPriority > activePriority) {
            putToSleep(activeProc);
            transferTo(newProc);
        } else {
            putToSleep(newProc);
        }
    }

    private void putToSleep(SqueakObject aProcess) {
        //Save the given process on the scheduler process list for its priority.
        int priority = aProcess.getPointerI(Squeak.Proc_priority).intValue();
        SqueakObject processLists = getScheduler().getPointerNI(Squeak.ProcSched_processLists);
        SqueakObject processList = processLists.getPointerNI(priority - 1);
        linkProcessToList(aProcess, processList);
    }

    private void transferTo(SqueakObject newProc) {
        //Record a process to be awakened on the next interpreter cycle.
        SqueakObject sched = getScheduler();
        SqueakObject oldProc = sched.getPointerNI(Squeak.ProcSched_activeProcess);
        sched.setPointer(Squeak.ProcSched_activeProcess, newProc);
        oldProc.setPointer(Squeak.Proc_suspendedContext, vm.activeContext);
        //int prio= vm.intFromSmall((Integer)newProc.pointers[Squeak.Proc_priority]);
        //System.err.println("Transfer to priority " + prio + " at byteCount " + vm.byteCount);
        //if (prio==8)
        //    vm.dumpStack();
        vm.newActiveContext(newProc.getPointerNI(Squeak.Proc_suspendedContext));
        //System.err.println("new pc is " + vm.pc + "; method offset= " + ((vm.method.pointers.length+1)*4));
        newProc.setPointer(Squeak.Proc_suspendedContext, vm.nilObj);
        vm.reclaimableContextCount = 0;
    }

    private SqueakObject pickTopProcess() { // aka wakeHighestPriority
        //Return the highest priority process that is ready to run.
        //Note: It is a fatal VM error if there is no runnable process.
        SqueakObject schedLists = getScheduler().getPointerNI(Squeak.ProcSched_processLists);
        int p = schedLists.pointersSize() - 1;  // index of last indexable field"
        p = p - 1;
        SqueakObject processList = schedLists.getPointerNI(p);
        while (isEmptyList(processList)) {
            p = p - 1;
            if (p < 0) {
                return vm.nilObj; //self error: 'scheduler could not find a runnable process' ].
            }
            processList = schedLists.getPointerNI(p);
        }
        return removeFirstLinkOfList(processList);
    }

    private void linkProcessToList(SqueakObject proc, SqueakObject aList) {
        // Add the given process to the given linked list and set the backpointer
        // of process to its new list."
        if (isEmptyList(aList)) {
            aList.setPointer(Squeak.LinkedList_firstLink, proc);
        } else {
            SqueakObject lastLink = aList.getPointerNI(Squeak.LinkedList_lastLink);
            lastLink.setPointer(Squeak.Link_nextLink, proc);
        }
        aList.setPointer(Squeak.LinkedList_lastLink, proc);
        proc.setPointer(Squeak.Proc_myList, aList);
    }

    private boolean isEmptyList(SqueakObject aLinkedList) {
        return aLinkedList.getPointerNI(Squeak.LinkedList_firstLink) == vm.nilObj;
    }

    private SqueakObject removeFirstLinkOfList(SqueakObject aList) {
        //Remove the first process from the given linked list."
        SqueakObject first = aList.getPointerNI(Squeak.LinkedList_firstLink);
        SqueakObject last = aList.getPointerNI(Squeak.LinkedList_lastLink);
        if (first == last) {
            aList.setPointer(Squeak.LinkedList_firstLink, vm.nilObj);
            aList.setPointer(Squeak.LinkedList_lastLink, vm.nilObj);
        } else {
            SqueakObject next = first.getPointerNI(Squeak.Link_nextLink);
            aList.setPointer(Squeak.LinkedList_firstLink, next);
        }
        first.setPointer(Squeak.Link_nextLink, vm.nilObj);
        return first;
    }

    private SqueakObject registerSemaphore(int specialObjSpec) {
        SqueakObject sema = (SqueakObject) vm.top();
        if (isA(sema, Squeak.splOb_ClassSemaphore)) {
            vm.specialObjects[specialObjSpec] = sema;
        } else {
            vm.specialObjects[specialObjSpec] = vm.nilObj;
        }
        return (SqueakObject) vm.stackValue(1);
    }

    private Object primitiveSignalAtMilliseconds() { //Delay signal:atMs:
        int msTime = stackInteger(0);
        Object sema = stackNonInteger(1);
        Object rcvr = stackNonInteger(2);
        if (!success) {
            return vm.nilObj;
        }

        //System.err.println("Signal at " + msTime);
        //vm.dumpStack();
        if (isA(sema, Squeak.splOb_ClassSemaphore)) {
            vm.specialObjects[Squeak.splOb_TheTimerSemaphore] = sema;
            vm.nextWakeupTick = msTime;
        } else {
            vm.specialObjects[Squeak.splOb_TheTimerSemaphore] = vm.nilObj;
            vm.nextWakeupTick = 0;
        }
        return rcvr;
    }

    //Other Primitives

    private Integer millisecondClockValue() {
        //Return the value of the millisecond clock as an integer.
        //Note that the millisecond clock wraps around periodically.
        //The range is limited to SmallInteger maxVal / 2 to allow
        //delays of up to that length without overflowing a SmallInteger."
        return SqueakVM.smallFromInt(((int) (System.currentTimeMillis() & (long) (SqueakVM.maxSmallInt >> 1))));
    }

    private boolean beDisplay(int argCount) {
        SqueakObject displayObj = (SqueakObject) vm.top();
        SqueakVM.FormCache disp = vm.newFormCache(displayObj);
        if (disp.squeakForm == null) {
            return false;
        }
        vm.specialObjects[Squeak.splOb_TheDisplay] = displayObj;
        displayBitmap = disp.bits;
        vm.popN(argCount);

        SqueakLogger.log_D("beDisplay: " + displayObj.toString());
        SqueakLogger.log_D(SqueakLogger.LOG_BLOCK_HEADER);
        SqueakLogger.log_D("    | Display size: " + disp.width + "@" + disp.height);
        SqueakLogger.log_D("    | Display depth: " + disp.depth);
        SqueakLogger.log_D("    | Display pixPerWord: " + disp.pixPerWord);
        SqueakLogger.log_D("    | Display pitch: " + disp.pitch);
        SqueakLogger.log_D("    | Display bits length: " + disp.bits.length);
        SqueakLogger.log_D(SqueakLogger.LOG_BLOCK_ENDDER);

        boolean remap = false;
        if (theDisplay != null) {
            remap = true;
        }

        if (remap) {
            Dimension requestedExtent = new Dimension(disp.width, disp.height);
            SqueakLogger.log_D("Squeak: changing screen size to " + disp.width + "@" + disp.height);
            if (theDisplay.getExtent().width != requestedExtent.width
                    && theDisplay.getExtent().height != requestedExtent.height) {
                SqueakLogger.log_E("Screen size mismatch! Rechanging screen size to " + disp.width + "@" + disp.height);
                theDisplay.setExtent(requestedExtent);
            }
        } else {
            // bind Screen
            theDisplay = new Screen("JSqueak", disp.width, disp.height, disp.depth, vm);
            theDisplay.getFrame().addWindowListener(new WindowAdapter() {
                                                        public void windowClosing(WindowEvent evt) {
                                                            // TODO ask before shutdown
                                                            // FIXME at least lock out quitting until concurrent image save has finished
                                                            theDisplay.exit();
                                                        }
                                                    }
            );
        }


        displayBitmapFromOrg = new int[displayBitmap.length];
        copyBitmapIntToInt(displayBitmap, displayBitmapFromOrg,
                new Rectangle(0, 0, disp.width, disp.height), disp.pitch, disp.depth);


        theDisplay.setBitsV2(displayBitmapFromOrg, disp.depth);
        if (!remap) {
            theDisplay.open();
        }
        return true;
    }

    private void copyImageFromOld(int[] old, int[] after) {
        if (old.length <= after.length) {
            for (int i = 0; i < old.length; i++) {
                after[i] = old[i];
            }
        } else {
            for (int i = 0; i < after.length; i++) {
                after[i] = old[i];
            }
        }
    }

    private boolean beCursor(int argCount) {
        // For now we ignore the white outline form (maskObj)
        if (theDisplay == null) {
            return true;
        }
        SqueakObject cursorObj, maskObj;
        if (argCount == 0) {
            cursorObj = stackNonInteger(0);
            maskObj = vm.nilObj;
        } else {
            cursorObj = stackNonInteger(1);
            maskObj = stackNonInteger(0);
        }
        SqueakVM.FormCache cursorForm = vm.newFormCache(cursorObj);
        if (!success || cursorForm.squeakForm == null) {
            return false;
        }
        //Following code for offset is not yet used...
        SqueakObject offsetObj = checkNonSmallInt(cursorObj.getPointer(4));
        if (!isA(offsetObj, Squeak.splOb_ClassPoint)) {
            return false;
        }
        int offsetX = checkSmallInt(offsetObj.pointers[0]);
        int offsetY = checkSmallInt(offsetObj.pointers[1]);
        if (!success) {
            return false;
        }
        //Current cursor code in Screen expects cursor and mask to be packed in cursorBytes
        //For now we make them be equal copies of incoming 16x16 cursor
        int cursorBitsSize = cursorForm.bits.length;
        byte[] cursorBytes = new byte[8 * cursorBitsSize];
        copyBitmapToByteArray(cursorForm.bits, cursorBytes,
                new Rectangle(0, 0, cursorForm.width, cursorForm.height),
                cursorForm.pitch, cursorForm.depth);
        for (int i = 0; i < (cursorBitsSize * 4); i++) {
            cursorBytes[i + (cursorBitsSize * 4)] = cursorBytes[i];
        }
        theDisplay.setCursor(cursorBytes, BWMask);
        return true;
    }

    private boolean primitiveYield(int numArgs) {
        // halts execution until EHT callbacks notify us
        long millis = 100;
        if (numArgs > 1) {
            return false;
        }
        if (numArgs > 0) {
            // But, for now, wait time is ignored...
            int micros = stackInteger(0);
            if (!success) {
                return false;
            }
            vm.pop();
            millis = micros / 1000;
        }
        // try { synchronized (vm) { vm.wait(); }
        //         } catch (InterruptedException e) { }
        // TODO how to handle third-party interruptions?
        try {
            synchronized (SqueakVM.class) {
                while (!vm.isScreenEvent()) {
                    SqueakVM.class.wait(millis);
                }
            }
        } catch (InterruptedException e) {
        }
        return true;
    }

    private boolean primitiveCopyBits(SqueakObject rcvr, int argCount) {
        // no rcvr class check, to allow unknown subclasses (e.g. under Turtle)
        if (!bitbltTable.loadBitBlt(rcvr, argCount, false, (SqueakObject) vm.specialObjects[Squeak.splOb_TheDisplay])) {
            return false;
        }

        Rectangle affectedArea = bitbltTable.copyBits();
        if (affectedArea != null && theDisplay != null) {
            copyBitmapIntToInt(displayBitmap, displayBitmapFromOrg, affectedArea,
                    bitbltTable.dest.pitch, bitbltTable.dest.depth);
            theDisplay.redisplay(false, affectedArea);
        }
        if (bitbltTable.combinationRule == 22 || bitbltTable.combinationRule == 32) {
            vm.popNandPush(2, SqueakVM.smallFromInt(bitbltTable.bitCount));
        }
        return true;
    }

    // FIXME (copyBitmapToByteArray)
    private void copyBitmapToByteArray(int[] words, byte[] bytes, Rectangle rect, int raster, int depth) {
        //Copy our 32-bit words into a byte array  until we find out
        // how to make AWT happy with int buffers
        if (depth == 1) {
            //System.out.println("copyBitmapToByteArray 1bit mode");
            copyBitmapMode1BitToByte(words, bytes, rect, raster, depth);
        } else if (depth == 8) {
            //System.out.println("copyBitmapToByteArray 8bit mode");
            copyBitmapMode8BitToByte(words, bytes, rect, raster, depth);
        }
    }

    /**
     * 32 pixel/integer => 8 pixel/byte
     */
    private void copyBitmapMode1BitToByte(int[] words, byte[] bitmapData, Rectangle rect, int raster, int depth) {
        int word;
        for (int i = 0; i < words.length; i++) {
            word = (words[i]); // actual 32 pixel
            for (int j = 0; j < 4; j++) {
                int pixelIndex = i * 4 + j;
                // 8 pixel per byte
                bitmapData[pixelIndex] = (byte) ((word >>> ((3 - j) * 8)) & 255);
            }
        }
    }

    /**
     * 4 pixel/integer => 1 pixel/byte
     */
    private void copyBitmapMode8BitToByte(int[] words, byte[] bitmapData, Rectangle rect, int raster, int depth) {
        int word;
        for (int i = 0; i < words.length; i++) {
            word = (words[i]); // actual 4 pixel
            for (int j = 0; j < 4; j++) {
                int pixelIndex = i * 4 + j;
                // 1 pixel per byte
                bitmapData[pixelIndex] = (byte) ((word >>> ((3 - j) * 8)) & 255);
            }
        }
    }


    /**
     * direct copy bitblt bitmap to int buffer
     */
    private void copyBitmapIntToInt(int[] words, int[] bitmapData, Rectangle rect, int raster, int depth) {
        for (int i = 0; i < words.length; i++) {
            bitmapData[i] = words[i];
        }
    }

    private SqueakObject primitiveMousePoint() {
        SqueakObject pointClass = (SqueakObject) vm.specialObjects[Squeak.splOb_ClassPoint];
        SqueakObject newPoint = vm.instantiateClass(pointClass, 0);
        Point lastMouse = theDisplay.getLastMousePoint();
        newPoint.setPointer(Squeak.Point_x, SqueakVM.smallFromInt(lastMouse.x));
        newPoint.setPointer(Squeak.Point_y, SqueakVM.smallFromInt(lastMouse.y));
        return newPoint;
    }

    private Integer primitiveMouseButtons() {
        return SqueakVM.smallFromInt(theDisplay.getLastMouseButtonStatus());
    }

    private Object primitiveKbdNext() {
        return SqueakVM.smallFromInt(theDisplay.keyboardNext());
    }

    private Object primitiveKbdPeek() {
        if (theDisplay == null) {
            return vm.nilObj;
        }
        int peeked = theDisplay.keyboardPeek();
        return peeked == 0 ? (Object) vm.nilObj : SqueakVM.smallFromInt(peeked);
    }

    private SqueakObject primitiveArrayBecome(boolean doBothWays) {
        // Should flush method cache
        SqueakObject rcvr = stackNonInteger(1);
        SqueakObject arg = stackNonInteger(0);
        if (!success) {
            return rcvr;
        }
        success = image.bulkBecome(rcvr.pointers, arg.pointers, doBothWays);
        return rcvr;
    }

    private SqueakObject primitiveSomeObject() {
        return image.nextInstance(0, null);
    }

    private SqueakObject primitiveSomeInstance(SqueakObject sqClass) {
        return image.nextInstance(0, sqClass);
    }

    private Object primitiveNextObject(SqueakObject priorObject) {
        SqueakObject nextObject = image.nextInstance(image.otIndexOfObject(priorObject) + 1, null);
        if (nextObject == vm.nilObj) {
            return SqueakVM.smallFromInt(0);
        }
        return nextObject;
    }

    private SqueakObject primitiveNextInstance(SqueakObject priorInstance) {
        SqueakObject sqClass = (SqueakObject) priorInstance.sqClass;
        return image.nextInstance(image.otIndexOfObject(priorInstance) + 1, sqClass);
    }

    //  region more-primitive-for-squeak

    private boolean primitiveSetFullScreen() {
        Object argOop = vm.top();

        if (argOop == null) {
            return false;
        }

        if (argOop == vm.trueObj) {
            System.out.println("invoke :: primitiveSetFullScreen on");
        } else {
            System.out.println("invoke :: primitiveSetFullScreen off");
        }

        vm.pop();
        return true;
    }

    private boolean primitiveScreenSize() {
        int width = 640;
        int height = 480;
        if (theDisplay != null && theDisplay.fExtent != null) {
            width = theDisplay.fExtent.width;
            height = theDisplay.fExtent.height;
        }
        SqueakLogger.log_D("primitiveScreenSize width: " + width + " height: " + height);
        return popNandPushIfOK(1, makePointWithXandY(SqueakVM.smallFromInt(width), SqueakVM.smallFromInt(height))); // actualScreenSize
    }

    private boolean primitiveScanCharacters() {
        return false;
    }

    private void primitiveDebug() {
        SqueakLogger.log_D("primitiveDebug");
    }

    // endregion more-primitive-for-squeak

    private boolean relinquishProcessor() {
        int periodInMicroseconds = stackInteger(0); //NOTE: argument is *ignored*
        vm.pop();
        //        Thread.currentThread().yield();
        //        try {vm.wait(50L); } // 50 millisecond pause
        //            catch (InterruptedException e) {}
        return true;
    }

    Object primSeconds() {
        long currentTimeMillis = System.currentTimeMillis();
        return squeakSeconds(currentTimeMillis);
    }

    // -- Some support methods -----------------------------------------------------------

    PrimitiveFailedException primitiveFailed() {
        return PrimitiveFailed;
    }

    /**
     * FIXME: surely something better can be devised?
     * Idea: make argCount a field, then this method
     * needs no argument
     */
    Object stackReceiver(int argCount) {
        return vm.stackValue(argCount);
    }

    private boolean popNandPushIfOK(int nToPop, Object returnValue) {
        if (!success || returnValue == null) {
            return false;
        }
        vm.popNandPush(nToPop, returnValue);
        return true;
    }

    private boolean pop2andDoBoolIfOK(boolean bool) {
        vm.success = success;
        return vm.pushBoolAndPeek(bool);
    }


    private boolean popNandPushIntIfOK(int nToPop, int returnValue) {
        return popNandPushIfOK(nToPop, SqueakVM.smallFromInt(returnValue));
    }

    boolean popNandPushFloatIfOK(int nToPop, double returnValue) {
        if (!success) {
            return false;
        }
        return popNandPushIfOK(nToPop, makeFloat(returnValue));
    }

    private boolean primitiveTruncate() {
        double floatVal = stackFloat(0);
        if (!(-1073741824.0 <= floatVal) && (floatVal <= 1073741823.0)) {
            return false;
        }
        vm.popNandPush(1, SqueakVM.smallFromInt((new Double(floatVal)).intValue())); //**must be a better way
        return true;
    }
}
