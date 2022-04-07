/*
SqueakVM.java
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

import org.jsqueak.input.ClipboardManager;
import org.jsqueak.uilts.SqueakLogger;

import java.io.FileInputStream;
import java.util.Arrays;

/**
 * @author Daniel Ingalls
 * <p>
 * The virtual machinery for executing Squeak bytecode.
 */
public class SqueakVM {

    // Global instance object for convenient
    public static SqueakVM INSTANCE = null;

    // 31-bit small Integers, range:
    public static int minSmallInt = -0x40000000;
    public static int maxSmallInt = 0x3FFFFFFF;
    public static int nonSmallInt = -0x50000000; //non-small and neg(so non pos32 too)
    public static int millisecondClockMask = maxSmallInt >> 1; //keeps ms logic in small int range

    public static int minCachedInt = -2000;
    public static int maxCachedInt = 4000;

    // static state:
    public static SqueakImage image;
    public static SqueakPrimitiveHandler primHandler;
    public static final ObjectMemory objectMemory = new ObjectMemory();

    public static SqueakObject nilObj;
    public static SqueakObject falseObj;
    public static SqueakObject trueObj;
    public static Object[] specialObjects;
    public static Object[] specialSelectors;

    // dynamic state:
    Object receiver = nilObj;
    SqueakObject activeContext = nilObj;
    SqueakObject homeContext = nilObj;
    int sp;
    SqueakObject method = nilObj;
    byte[] methodBytes;
    int pc;
    boolean success;
    private SqueakObject freeContexts;
    private SqueakObject freeLargeContexts;
    int reclaimableContextCount; //Not #available, but how far down the current stack is recyclable
    SqueakObject verifyAtSelector;
    SqueakObject verifyAtClass;

    private boolean screenEvent = false;

    int lowSpaceThreshold;
    private int interruptCheckCounter;
    private int interruptCheckCounterFeedBackReset;
    private int interruptChecksEveryNms;
    private int nextPollTick;
    int nextWakeupTick;
    private int lastTick;
    private int interruptKeycode;
    private boolean interruptPending;
    private boolean semaphoresUseBufferA;
    private int semaphoresToSignalCountA;
    private int semaphoresToSignalCountB;
    private boolean deferDisplayUpdates;
    private int pendingFinalizationSignals;

    // Component of VM
    public final ClipboardManager clipboardManager = new ClipboardManager();


    public static class MethodCacheEntry {
        SqueakObject lkupClass;
        SqueakObject selector;
        SqueakObject method;
        int primIndex;
        int tempCount;
    }

    static int methodCacheSize = 1024; // must be power of two
    static int methodCacheMask = methodCacheSize - 1; // so this is a mask
    static int randomish = 0;

    MethodCacheEntry[] methodCache = new MethodCacheEntry[methodCacheSize];

    void initMethodCache() {
        methodCache = new MethodCacheEntry[methodCacheSize];
        for (int i = 0; i < methodCacheSize; i++) {
            methodCache[i] = new MethodCacheEntry();
        }
    }

    int byteCount = 0;
    FileInputStream byteTracker;
    int nRecycledContexts = 0;
    int nAllocatedContexts = 0;
    Object[] stackedReceivers = new Object[100];
    Object[] stackedSelectors = new Object[100];


    public SqueakVM(SqueakImage anImage) {
        // canonical creation
        image = anImage;
        primHandler = new SqueakPrimitiveHandler(this);
        loadImageState();
        initVMState();
        loadInitialContext();
    }

    void clearCaches() {
        // Some time store null above SP in contexts
        primHandler.clearAtCache();
        clearMethodCache();
        freeContexts = nilObj;
        freeLargeContexts = nilObj;
    }

    private void loadImageState() {
        SqueakObject specialObjectsArray = image.specialObjectsArray;
        specialObjects = specialObjectsArray.pointers;
        nilObj = getSpecialObject(Squeak.splOb_NilObject);
        falseObj = getSpecialObject(Squeak.splOb_FalseObject);
        trueObj = getSpecialObject(Squeak.splOb_TrueObject);
        SqueakObject ssObj = getSpecialObject(Squeak.splOb_SpecialSelectors);
        specialSelectors = ssObj.pointers;
    }

    public SqueakObject getSpecialObject(int zeroBasedIndex) {
        return (SqueakObject) specialObjects[zeroBasedIndex];
    }

    private void initVMState() {
        interruptCheckCounter = 0;
        interruptCheckCounterFeedBackReset = 1000;
        interruptChecksEveryNms = 3;
        nextPollTick = 0;
        nextWakeupTick = 0;
        lastTick = 0;
        interruptKeycode = 2094;  //"cmd-."
        interruptPending = false;
        semaphoresUseBufferA = true;
        semaphoresToSignalCountA = 0;
        semaphoresToSignalCountB = 0;
        deferDisplayUpdates = false;
        pendingFinalizationSignals = 0;
        freeContexts = nilObj;
        freeLargeContexts = nilObj;
        reclaimableContextCount = 0;
        initMethodCache();
        initByteCodeTable();

        clipboardManager.reset();
    }

    private void loadInitialContext() {
        SqueakObject schedAssn = getSpecialObject(Squeak.splOb_SchedulerAssociation);
        SqueakObject sched = schedAssn.getPointerNI(Squeak.Assn_value);
        SqueakObject proc = sched.getPointerNI(Squeak.ProcSched_activeProcess);
        activeContext = proc.getPointerNI(Squeak.Proc_suspendedContext);
        if (activeContext != null) {
            try {
                fetchContextRegisters(activeContext);
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
        reclaimableContextCount = 0;
    }

    public void newActiveContext(SqueakObject newContext) {
        storeContextRegisters();
        activeContext = newContext; //We're off and running...
        fetchContextRegisters(newContext);
    }

    public void fetchContextRegisters(SqueakObject ctxt) {
        Object meth = ctxt.getPointer(Squeak.Context_method);
        if (InterpreterHelper.isSTInteger(meth)) {
            //if the Method field is an integer, activeCntx is a block context
            homeContext = (SqueakObject) ctxt.getPointer(Squeak.BlockContext_home);
            meth = homeContext.getPointerNI(Squeak.Context_method);
        } else {
            //otherwise home==ctxt
            // if (! primHandler.isA(meth,Squeak.splOb_ClassCompiledMethod))
            //    meth= meth; // <-- break here
            homeContext = (SqueakObject) ctxt;
        }
        receiver = homeContext.getPointer(Squeak.Context_receiver);
        method = (SqueakObject) meth;
        methodBytes = (byte[]) method.bits;
        pc = decodeSqueakPC(ctxt.getPointerI(Squeak.Context_instructionPointer), method);
        if (pc < -1)
            dumpStack();
        sp = decodeSqueakSP(ctxt.getPointerI(Squeak.Context_stackPointer));
    }

    public void storeContextRegisters() {
        //Save pc, sp into activeContext object, prior to change of context
        //   see fetchContextRegisters for symmetry
        //   expects activeContext, pc, sp, and method state vars to still be valid
        activeContext.setPointer(Squeak.Context_instructionPointer, encodeSqueakPC(pc, method));
        activeContext.setPointer(Squeak.Context_stackPointer, encodeSqueakSP(sp));
    }

    public Integer encodeSqueakPC(int intPC, SqueakObject aMethod) {
        // Squeak pc is offset by header and literals
        // and 1 for z-rel addressing, and 1 for pre-increment of fetch
        return InterpreterHelper.smallFromInt(intPC + (((aMethod.methodNumLits() + 1) * 4) + 1 + 1));
    }

    public int decodeSqueakPC(Integer squeakPC, SqueakObject aMethod) {
        return squeakPC - (((aMethod.methodNumLits() + 1) * 4) + 1 + 1);
    }

    public Integer encodeSqueakSP(int intSP) {
        // sp is offset by tempFrameStart, -1 for z-rel addressing
        return InterpreterHelper.smallFromInt(intSP - (Squeak.Context_tempFrameStart - 1));
    }

    public int decodeSqueakSP(Integer squeakPC) {
        return squeakPC + (Squeak.Context_tempFrameStart - 1);
    }

    /*@Deprecated
    public static int intFromSmall(Integer smallInt) {
        //  Unnecessary unboxing
        return smallInt.intValue();
    }*/

    // MEMORY ACCESS:
    public SqueakObject getClass(Object obj) {
        if (InterpreterHelper.isSTInteger(obj))
            return getSpecialObject(Squeak.splOb_ClassInteger);
        return ((SqueakObject) obj).getSqClass();
    }

    // STACKFRAME ACCESS:
    public boolean isContext(SqueakObject obj) {
        //either block or methodContext
        if (obj.sqClass == specialObjects[Squeak.splOb_ClassMethodContext])
            return true;
        if (obj.sqClass == specialObjects[Squeak.splOb_ClassBlockContext])
            return true;
        return false;
    }

    public boolean isMethodContext(SqueakObject obj) {
        if (obj.sqClass == specialObjects[Squeak.splOb_ClassMethodContext])
            return true;
        return false;
    }

    public Object pop() {
        //Note leaves garbage above SP.  Serious reclaim should store nils above SP
        return activeContext.pointers[sp--];
    }

    public void popN(int nToPop) {
        sp -= nToPop;
    }

    public void push(Object oop) {
        activeContext.pointers[++sp] = oop;
    }

    public void popNandPush(int nToPop, Object oop) {
        activeContext.pointers[sp -= nToPop - 1] = oop;
    }

    public Object top() {
        return activeContext.pointers[sp];
    }

    public Object stackValue(int depthIntoStack) {
        return activeContext.pointers[sp - depthIntoStack];
    }

    // INNER BYTECODE INTERPRETER:
    public int nextByte() {
        return methodBytes[++pc] & 0xff;
    }

    public void run() throws java.io.IOException {
        int b, b2;
        while (true) {
            //...Here's the basic evaluator loop...'
            //        printContext();
            //        byteCount++;
            //        int b= nextByte();
            b = methodBytes[++pc] & 0xff;

            BytecodeExcutor bytecodeExcutor = bytecodeTable[b];
            bytecodeExcutor.excute(b);
            continue;
        }
    }

    public void checkForInterrupts() {
        //Check for interrupts at sends and backward jumps
        SqueakObject sema;
        int now;
        if (interruptCheckCounter-- > 0) {
            return; //only really check every 100 times or so
        }
        //Mask so same wrap as primitiveMillisecondClock
        now = (int) (System.currentTimeMillis() & (long) millisecondClockMask);
        if (now < lastTick) {
            //millisecond clock wrapped"
            nextPollTick = now + (nextPollTick - lastTick);
            if (nextWakeupTick != 0) {
                nextWakeupTick = now + (nextWakeupTick - lastTick);
            }
        }
        //Feedback logic attempts to keep interrupt response around 3ms...
        if ((now - lastTick) < interruptChecksEveryNms) { //wrapping is not a concern

            interruptCheckCounterFeedBackReset += 10;
        } else {
            if (interruptCheckCounterFeedBackReset <= 1000) {
                interruptCheckCounterFeedBackReset = 1000;
            } else {
                interruptCheckCounterFeedBackReset -= 12;
            }
        }
        interruptCheckCounter = interruptCheckCounterFeedBackReset; //reset the interrupt check counter"
        lastTick = now; //used to detect wraparound of millisecond clock
        //  if (signalLowSpace) {
        //            signalLowSpace= false; //reset flag
        //            sema= getSpecialObject(Squeak.splOb_TheLowSpaceSemaphore);
        //            if (sema != nilObj) synchronousSignal(sema); }
        //  if (now >= nextPollTick) {
        //            ioProcessEvents(); //sets interruptPending if interrupt key pressed
        //            nextPollTick= now + 500; } //msecs to wait before next call to ioProcessEvents"
        if (interruptPending) {
            interruptPending = false; //reset interrupt flag
            sema = getSpecialObject(Squeak.splOb_TheInterruptSemaphore);
            if (sema != nilObj) {
                primHandler.synchronousSignal(sema);
            }
        }
        if ((nextWakeupTick != 0) && (now >= nextWakeupTick)) {
            nextWakeupTick = 0; //reset timer interrupt
            sema = getSpecialObject(Squeak.splOb_TheTimerSemaphore);
            if (sema != nilObj) {
                primHandler.synchronousSignal(sema);
            }
        }
        //  if (pendingFinalizationSignals > 0) { //signal any pending finalizations
        //            sema= getSpecialObject(Squeak.splOb_ThefinalizationSemaphore);
        //            pendingFinalizationSignals= 0;
        //            if (sema != nilObj) primHandler.synchronousSignal(sema); }
        //  if ((semaphoresToSignalCountA > 0) || (semaphoresToSignalCountB > 0)) {
        //            signalExternalSemaphores(); }  //signal all semaphores in semaphoresToSignal
    }

    public void jumpif(boolean condition, int delta) {
        Object top = pop();
        if (top == (condition ? trueObj : falseObj)) {
            pc += delta;
            return;
        }
        if (top == (condition ? falseObj : trueObj)) {
            return;
        }
        push(top); //Uh-oh it's not even a boolean (that we know of ;-).  Restore stack...
        send((SqueakObject) specialObjects[Squeak.splOb_SelectorMustBeBoolean], 1, false);
    }

    public void sendSpecial(int lobits) {
        send((SqueakObject) specialSelectors[lobits * 2],
                (Integer) specialSelectors[(lobits * 2) + 1],
                false);   //specialSelectors is  {...sel,nArgs,sel,nArgs,...)
    }

    public void extendedPush(int nextByte) {
        int lobits = nextByte & 63;
        switch (nextByte >> 6) {
            case 0:
                push(((SqueakObject) receiver).getPointer(lobits));
                break;
            case 1:
                push(homeContext.getPointer(Squeak.Context_tempFrameStart + lobits));
                break;
            case 2:
                push(method.methodGetLiteral(lobits));
                break;
            case 3:
                push(((SqueakObject) method.methodGetLiteral(lobits)).getPointer(Squeak.Assn_value));
                break;
        }
    }

    public void extendedStore(int nextByte) {
        int lobits = nextByte & 63;
        switch (nextByte >> 6) {
            case 0:
                ((SqueakObject) receiver).setPointer(lobits, top());
                break;
            case 1:
                homeContext.setPointer(Squeak.Context_tempFrameStart + lobits, top());
                break;
            case 2:
                nono();
                break;
            case 3:
                ((SqueakObject) method.methodGetLiteral(lobits)).setPointer(Squeak.Assn_value, top());
                break;
        }
    }

    public void extendedStorePop(int nextByte) {
        int lobits = nextByte & 63;
        switch (nextByte >> 6) {
            case 0:
                ((SqueakObject) receiver).setPointer(lobits, pop());
                break;
            case 1:
                homeContext.setPointer(Squeak.Context_tempFrameStart + lobits, pop());
                break;
            case 2:
                nono();
                break;
            case 3:
                ((SqueakObject) method.methodGetLiteral(lobits)).setPointer(Squeak.Assn_value, pop());
                break;
        }
    }

    public void doubleExtendedDoAnything(int nextByte) {
        int byte3 = nextByte();
        switch (nextByte >> 5) {
            case 0:
                send(method.methodGetSelector(byte3), nextByte & 31, false);
                break;
            case 1:
                send(method.methodGetSelector(byte3), nextByte & 31, true);
                break;
            case 2:
                push(((SqueakObject) receiver).getPointer(byte3));
                break;
            case 3:
                push(method.methodGetLiteral(byte3));
                break;
            case 4:
                push(((SqueakObject) method.methodGetLiteral(byte3)).getPointer(Squeak.Assn_key));
                break;
            case 5:
                ((SqueakObject) receiver).setPointer(byte3, top());
                break;
            case 6:
                ((SqueakObject) receiver).setPointer(byte3, pop());
                break;
            case 7:
                ((SqueakObject) method.methodGetLiteral(byte3)).setPointer(Squeak.Assn_key, top());
                break;
        }
    }

    public void doReturn(Object returnValue, SqueakObject targetContext) {
        if (targetContext == nilObj) {
            cannotReturn();
        }
        if (targetContext.getPointer(Squeak.Context_instructionPointer) == nilObj) {
            cannotReturn();
        }
        SqueakObject thisContext = activeContext;
        while (thisContext != targetContext) {
            if (thisContext == nilObj) {
                cannotReturn();
            }
            if (isUnwindMarked(thisContext)) {
                aboutToReturn(returnValue, thisContext);
            }
            thisContext = thisContext.getPointerNI(Squeak.Context_sender);
        }
        //No unwind to worry about, just peel back the stack (usually just to sender)
        SqueakObject nextContext;
        thisContext = activeContext;
        while (thisContext != targetContext) {
            nextContext = thisContext.getPointerNI(Squeak.Context_sender);
            thisContext.setPointer(Squeak.Context_sender, nilObj);
            thisContext.setPointer(Squeak.Context_instructionPointer, nilObj);
            if (reclaimableContextCount > 0) {
                reclaimableContextCount--;
                recycleIfPossible(thisContext);
            }
            thisContext = nextContext;
        }
        activeContext = thisContext;
        fetchContextRegisters(activeContext);
        push(returnValue);
        //System.err.println("***returning " + printString(returnValue));
    }

    public void cannotReturn() {
    }

    public boolean isUnwindMarked(SqueakObject ctxt) {
        return false;
    }

    public void aboutToReturn(Object obj, SqueakObject ctxt) {
    }

    public void nono() {
        throw new RuntimeException("bad code");
    }

    int stackInteger(int nDeep) {
        return checkSmallInt(stackValue(nDeep));
    }

    int checkSmallInt(Object maybeSmall) {
        // returns an int and sets success
        if (maybeSmall instanceof Integer) {
            return (Integer) maybeSmall;
        }
        success = false;
        return 1;
    }

    public boolean pop2AndPushIntResult(int intResult) {
        //Note returns sucess boolean
        if (!success) {
            return false;
        }
        Object smallInt = InterpreterHelper.smallFromInt(intResult);
        if (smallInt != null) {
            popNandPush(2, smallInt);
            return true;
        }
        return false;
    }

    public boolean pushBoolAndPeek(boolean boolResult) {
        //Peek ahead to see if next bytecode is a conditional jump
        if (!success) {
            return false;
        }
        int originalPC = pc;
        int nextByte = nextByte();
        if (nextByte >= 152 && nextByte < 160) {
            // It's a BFP
            popN(2);
            if (boolResult) {
                return true;
            } else {
                pc += (nextByte - 152 + 1);
            }
            return true;
        }
        if (nextByte == 172) {
            // It's a long BFP
            // Could check for all long cond jumps later
            popN(2);
            nextByte = nextByte();
            if (boolResult) {
                return true;
            } else {
                pc += nextByte;
            }
            return true;
        }
        popNandPush(2, boolResult ? trueObj : falseObj);
        pc = originalPC;
        return true;
    }



    public void send(SqueakObject selector, int argCount, boolean doSuper) {
        SqueakObject newMethod;
        int primIndex;
        Object newRcvr = stackValue(argCount);
        //if (printString(selector).equals("error:"))
        //  dumpStack();// <---break here
        //     int stackDepth=stackDepth();
        //     stackedReceivers[stackDepth]=newRcvr;
        //     stackedSelectors[stackDepth]=selector;
        SqueakObject lookupClass = getClass(newRcvr);
        if (doSuper) {
            lookupClass = method.methodClassForSuper();
            lookupClass = lookupClass.getPointerNI(Squeak.Class_superclass);
        }
        int priorSP = sp; // to check if DNU changes argCount
        MethodCacheEntry entry = findSelectorInClass(selector, argCount, lookupClass);
        newMethod = entry.method;
        primIndex = entry.primIndex;
        if (primIndex > 0) {
            //note details for verification of at/atput primitives
            verifyAtSelector = selector;
            verifyAtClass = lookupClass;
        }
        executeNewMethod(newRcvr, newMethod, argCount + (sp - priorSP), primIndex);
    } //DNU may affest argCount

    public MethodCacheEntry findSelectorInClass(SqueakObject selector, int argCount, SqueakObject startingClass) {
        MethodCacheEntry cacheEntry = findMethodCacheEntry(selector, startingClass);
        if (cacheEntry.method != null) {
            return cacheEntry; // Found it in the method cache
        }
        SqueakObject currentClass = startingClass;
        SqueakObject mDict;
        while (!(currentClass == nilObj)) {
            mDict = currentClass.getPointerNI(Squeak.Class_mdict);
            if (mDict == nilObj) {
                //                ["MethodDict pointer is nil (hopefully due a swapped out stub)
                //                        -- raise exception #cannotInterpret:."
                //                self createActualMessageTo: class.
                //                messageSelector _ self splObj: SelectorCannotInterpret.
                //                ^ self lookupMethodInClass: (self superclassOf: currentClass)]
            }
            SqueakObject newMethod = lookupSelectorInDict(mDict, selector);
            if (!(newMethod == nilObj)) {
                //load cache entry here and return
                cacheEntry.method = newMethod;
                cacheEntry.primIndex = newMethod.methodPrimitiveIndex();
                return cacheEntry;
            }
            currentClass = currentClass.getPointerNI(Squeak.Class_superclass);
        }

        //Cound not find a normal message -- send #doesNotUnderstand:
        //if (printString(selector).equals("zork"))
        //    System.err.println(printString(selector));
        SqueakObject dnuSel = getSpecialObject(Squeak.splOb_SelectorDoesNotUnderstand);
        if (selector == dnuSel) { // Cannot find #doesNotUnderstand: -- unrecoverable error.
            throw new RuntimeException("Recursive not understood error encountered");
        }
        SqueakObject dnuMsg = createActualMessage(selector, argCount, startingClass); //The argument to doesNotUnderstand:
        popNandPush(argCount, dnuMsg);
        return findSelectorInClass(dnuSel, 1, startingClass);
    }

    public SqueakObject createActualMessage(SqueakObject selector, int argCount, SqueakObject cls) {
        //Bundle up receiver, args and selector as a messageObject
        SqueakObject argArray = instantiateClass(getSpecialObject(Squeak.splOb_ClassArray), argCount);
        System.arraycopy(activeContext.pointers, sp - argCount + 1, argArray.pointers, 0, argCount); //copy args from stack
        SqueakObject message = instantiateClass(getSpecialObject(Squeak.splOb_ClassMessage), 0);
        message.setPointer(Squeak.Message_selector, selector);
        message.setPointer(Squeak.Message_arguments, argArray);
        if (message.pointers.length < 3) {
            return message; //Early versions don't have lookupClass
        }
        message.setPointer(Squeak.Message_lookupClass, cls);
        return message;
    }

    public SqueakObject lookupSelectorInDict(SqueakObject mDict, SqueakObject messageSelector) {
        //Returns a method or nilObject
        int dictSize = mDict.pointersSize();
        int mask = (dictSize - Squeak.MethodDict_selectorStart) - 1;
        int index = (mask & messageSelector.hash) + Squeak.MethodDict_selectorStart;
        // If there are no nils(should always be), then stop looping on second wrap.
        boolean hasWrapped = false;
        while (true) {
            SqueakObject nextSelector = mDict.getPointerNI(index);
            //System.err.println("index= "+index+" "+printString(nextSelector));
            if (nextSelector == messageSelector) {
                SqueakObject methArray = mDict.getPointerNI(Squeak.MethodDict_array);
                return methArray.getPointerNI(index - Squeak.MethodDict_selectorStart);
            }
            if (nextSelector == nilObj) {
                return nilObj;
            }
            if (++index == dictSize) {
                if (hasWrapped) {
                    return nilObj;
                }
                index = Squeak.MethodDict_selectorStart;
                hasWrapped = true;
            }
        }
    }

    public void executeNewMethod(Object newRcvr, SqueakObject newMethod, int argumentCount, int primitiveIndex) {
        if (primitiveIndex > 0) {
            if (tryPrimitive(primitiveIndex, argumentCount)) {
                return;  //Primitive succeeded -- end of story
            }
        }
        SqueakObject newContext = allocateOrRecycleContext(newMethod.methodNeedsLargeFrame());
        int methodNumLits = method.methodNumLits();
        //Our initial IP is -1, so first fetch gets bits[0]
        //The stored IP should be 1-based index of *next* instruction, offset by hdr and lits
        int newPC = -1;
        int tempCount = newMethod.methodTempCount();
        int newSP = tempCount;
        newSP += Squeak.Context_tempFrameStart - 1; //-1 for z-rel addressing
        newContext.setPointer(Squeak.Context_method, newMethod);
        //Following store is in case we alloc without init; all other fields get stored
        newContext.setPointer(Squeak.BlockContext_initialIP, nilObj);
        newContext.setPointer(Squeak.Context_sender, activeContext);
        //Copy receiver and args to new context
        //Note this statement relies on the receiver slot being contiguous with args...
        System.arraycopy(activeContext.pointers, sp - argumentCount, newContext.pointers, Squeak.Context_tempFrameStart - 1, argumentCount + 1);
        //...and fill the remaining temps with nil
        Arrays.fill(newContext.pointers, Squeak.Context_tempFrameStart + argumentCount, Squeak.Context_tempFrameStart + tempCount, nilObj);
        popN(argumentCount + 1);
        reclaimableContextCount++;
        storeContextRegisters();
        activeContext = newContext; //We're off and running...
        //      Following are more efficient than fetchContextRegisters in newActiveContext:
        homeContext = newContext;
        method = newMethod;
        methodBytes = (byte[]) method.bits;
        pc = newPC;
        sp = newSP;
        storeContextRegisters(); // not really necessary, I claim
        receiver = newContext.getPointer(Squeak.Context_receiver);
        if (receiver != newRcvr) {
            SqueakLogger.log_E("Receiver doesn't match");
        }
        checkForInterrupts();
    }

    public boolean tryPrimitive(int primIndex, int argCount) {
        if ((primIndex > 255) && (primIndex < 520)) {
            if (primIndex >= 264) {
                //return instvars
                popNandPush(1, ((SqueakObject) top()).getPointer(primIndex - 264));
                return true;
            } else {
                if (primIndex == 256)
                    return true; //return self
                if (primIndex == 257) {
                    popNandPush(1, trueObj); //return true
                    return true;
                }
                if (primIndex == 258) {
                    popNandPush(1, falseObj); //return false
                    return true;
                }
                if (primIndex == 259) {
                    popNandPush(1, nilObj); //return nil
                    return true;
                }
                popNandPush(1, InterpreterHelper.smallFromInt(primIndex - 261)); //return -1...2
                return true;
            }
        } else {
            int spBefore = sp;
            boolean success = primHandler.doPrimitive(primIndex, argCount);
            /*if (!success && primIndex != 19) {
                SqueakLogger.log_D("primitive failed at index: " + primIndex);
            }*/
//            if (success) {
//                if (primIndex>=81 && primIndex<=88) return success; // context switches and perform
//                if (primIndex>=43 && primIndex<=48) return success; // boolean peeks
//                if (sp != (spBefore-argCount))
//                    System.err.println("***Stack imbalance on primitive #" + primIndex); }
//            else{
//                if (sp != spBefore)
//                    System.err.println("***Stack imbalance on primitive #" + primIndex);
//                if (primIndex==103) return success; // scan chars
//                if (primIndex==230) return success; // yield
//                if (primIndex==19) return success; // fail
//                System.err.println("At bytecount " + byteCount + " failed primitive #" + primIndex);
//                if (primIndex==80) {
//                    dumpStack();
//                    int a=primIndex; } // <-- break here
//                }
            return success;
        }
    }

    public boolean primitivePerform(int argCount) {
        SqueakObject selector = (SqueakObject) stackValue(argCount - 1);
        Object rcvr = stackValue(argCount);
        //      NOTE: findNewMethodInClass may fail and be converted to #doesNotUnderstand:,
        //            (Whoah) so we must slide args down on the stack now, so that would work
        int trueArgCount = argCount - 1;
        int selectorIndex = sp - trueArgCount;
        Object[] stack = activeContext.pointers; // slide eveything down...
        System.arraycopy(stack, selectorIndex + 1, stack, selectorIndex, trueArgCount);
        sp--; // adjust sp accordingly
        MethodCacheEntry entry = findSelectorInClass(selector, trueArgCount, getClass(rcvr));
        SqueakObject newMethod = entry.method;
        executeNewMethod(rcvr, newMethod, newMethod.methodNumArgs(), entry.primIndex);
        return true;
    }

    public boolean primitivePerformWithArgs(SqueakObject lookupClass) {
        Object rcvr = stackValue(2);
        SqueakObject selector = (SqueakObject) stackValue(1);
        if (InterpreterHelper.isSTInteger(stackValue(0))) {
            return false;
        }
        SqueakObject args = (SqueakObject) stackValue(0);
        if (args.pointers == null) {
            return false;
        }
        int trueArgCount = args.pointers.length;
        System.arraycopy(args.pointers, 0, activeContext.pointers, sp - 1, trueArgCount);
        sp = sp - 2 + trueArgCount; //pop selector and array then push args
        MethodCacheEntry entry = findSelectorInClass(selector, trueArgCount, lookupClass);
        SqueakObject newMethod = entry.method;
        if (newMethod.methodNumArgs() != trueArgCount) {
            return false;
        }
        executeNewMethod(rcvr, newMethod, newMethod.methodNumArgs(), entry.primIndex);
        return true;
    }

    public boolean primitivePerformInSuperclass(SqueakObject lookupClass) {
        //verify that lookupClass is actually in reciver's inheritance
        SqueakObject currentClass = getClass(stackValue(3));
        while (currentClass != lookupClass) {
            currentClass = (SqueakObject) currentClass.pointers[Squeak.Class_superclass];
            if (currentClass == nilObj) {
                return false;
            }
        }
        pop(); //pop the lookupClass for now
        if (primitivePerformWithArgs(lookupClass)) {
            return true;
        }
        push(lookupClass); //restore lookupClass if failed
        return false;
    }

    public void recycleIfPossible(SqueakObject ctxt) {
        if (!isMethodContext(ctxt)) {
            return;
        }
        //if (isContext(ctxt)) return; //Defeats recycling of contexts
        if (ctxt.pointersSize() == (Squeak.Context_tempFrameStart + Squeak.Context_smallFrameSize)) {
            // Recycle small contexts
            ctxt.setPointer(0, freeContexts);
            freeContexts = ctxt;
        } else {
            // Recycle large contexts
            if (ctxt.pointersSize() != (Squeak.Context_tempFrameStart + Squeak.Context_largeFrameSize)) {
                // freeContexts=freeContexts;
                return;  //  <-- break here
            }
            ctxt.setPointer(0, freeLargeContexts);
            freeLargeContexts = ctxt;
        }
    }

    public SqueakObject allocateOrRecycleContext(boolean needsLarge) {
        //Return a recycled context or a newly allocated one if none is available for recycling."
        SqueakObject freebie;
        if (needsLarge) {
            if (freeLargeContexts != nilObj) {
                freebie = freeLargeContexts;
                freeLargeContexts = freebie.getPointerNI(0);
                nRecycledContexts++;
                return freebie;
            }
            nAllocatedContexts++;
            return instantiateClass((SqueakObject) specialObjects[Squeak.splOb_ClassMethodContext],
                    Squeak.Context_largeFrameSize);
        } else {
            if (freeContexts != nilObj) {
                freebie = freeContexts;
                freeContexts = freebie.getPointerNI(0);
                nRecycledContexts++;
                return freebie;
            }
            nAllocatedContexts++;
            return instantiateClass((SqueakObject) specialObjects[Squeak.splOb_ClassMethodContext],
                    Squeak.Context_smallFrameSize);
        }
    }

    public SqueakObject instantiateClass(int specialObjectClassIndex, int indexableSize) {
        return instantiateClass((SqueakObject) specialObjects[specialObjectClassIndex],
                indexableSize);
    }

    // FIXME: remove this method
    public SqueakObject instantiateClass(SqueakObject theClass, int indexableSize) {
        return new SqueakObject(image, theClass, indexableSize, nilObj);
    }

    public boolean clearMethodCache() {
        //clear method cache entirely (prim 89)
        for (int i = 0; i < methodCacheSize; i++) {
            methodCache[i].selector = null;   // mark it free
            methodCache[i].method = null;     // release the method
        }
        return true;
    }

    public boolean flushMethodCacheForSelector(SqueakObject selector) {
        //clear cache entries for selector (prim 119)
        for (int i = 0; i < methodCacheSize; i++) {
            if (methodCache[i].selector == selector) {
                methodCache[i].selector = null;   // mark it free
                methodCache[i].method = null;   // release the method
            }
        }
        return true;
    }

    public boolean flushMethodCacheForMethod(SqueakObject method) {
        //clear cache entries for selector (prim 116)
        for (int i = 0; i < methodCacheSize; i++) {
            if (methodCache[i].method == method) {
                methodCache[i].selector = null;   // mark it free
                methodCache[i].method = null;   // release the method
            }
        }
        return true;
    }

    public MethodCacheEntry findMethodCacheEntry(SqueakObject selector, SqueakObject lkupClass) {
        //Probe the cache, and return the matching entry if found
        //Otherwise return one that can be used (selector and class set) with method= null.
        //Initial probe is class xor selector, reprobe delta is selector
        //We don not try to optimize probe time -- all are equally 'fast' compared to lookup
        //Instead we randomize the reprobe so two or three very active conflicting entries
        //will not keep dislodging each other
        MethodCacheEntry entry;
        int nProbes = 4;
        randomish = (randomish + 1) % nProbes;
        int firstProbe = (selector.hash ^ lkupClass.hash) & methodCacheMask;
        int probe = firstProbe;
        for (int i = 0; i < 4; i++) {
            // 4 reprobes for now
            entry = methodCache[probe];
            if (entry.selector == selector && entry.lkupClass == lkupClass) {
                return entry;
            }
            if (i == randomish) {
                firstProbe = probe;
            }
            probe = (probe + selector.hash) & methodCacheMask;
        }
        entry = methodCache[firstProbe];
        entry.lkupClass = lkupClass;
        entry.selector = selector;
        entry.method = null;
        return entry;
    }

    public void printContext() {
        if ((byteCount % 100) == 0 && stackDepth() > 100) {
            System.err.println("******Stack depth over 100******");
            dumpStack();
            // byteCount= byteCount;  // <-- break here
        }
        //        if (mod(byteCount,1000) != 0) return;
        if (byteCount != -1) {
            return;
        }
        System.err.println();
        System.err.println(byteCount + " rcvr= " + printString(receiver));
        System.err.println("depth= " + stackDepth() + "; top= " + printString(top()));
        System.err.println("pc= " + pc + "; sp= " + sp + "; nextByte= " + (((byte[]) method.bits)[pc + 1] & 0xff));
        // if (byteCount==1764)
        //    byteCount= byteCount;  // <-- break here
    }

    int stackDepth() {
        SqueakObject ctxt = activeContext;
        int depth = 0;
        while ((ctxt = ctxt.getPointerNI(Squeak.Context_sender)) != nilObj) {
            depth = depth + 1;
        }
        return depth;
    }

    String printString(Object obj) {
        //Handles SqueakObjs and SmallInts as well
        if (obj == null) {
            return "null";
        }
        if (InterpreterHelper.isSTInteger(obj)) {
            return "=" + obj;
        } else {
            return ((SqueakObject) obj).asString();
        }
    }

    void dumpStack() {
        for (int i = 0; i < stackDepth(); i++) {
            if (stackedSelectors != null) {
                System.err.println(stackedReceivers[i] + " >> " + stackedSelectors[i]);
            }
        }
    }

    FormCache newFormCache(SqueakObject aForm) {
        return new FormCache(aForm);
    }

    FormCache newFormCache() {
        return new FormCache();
    }

    public static class FormCache {
        SqueakObject squeakForm;
        int[] bits;
        int width;
        int height;
        int depth;
        boolean msb;
        int pixPerWord;
        int pitch; // aka raster

        FormCache() {
        }

        FormCache(SqueakObject obj) {
            this.loadFrom(obj);
        }

        boolean loadFrom(Object aForm) {
            //We do not reload if this is the same form as before
            if (squeakForm == aForm) {
                return true;
            }
            squeakForm = null; //Marks this as failed until very end...
            if (InterpreterHelper.isSTInteger(aForm)) {
                return false;
            }
            Object[] formPointers = ((SqueakObject) aForm).pointers;
            if (formPointers == null || formPointers.length < 4) {
                return false;
            }
            for (int i = 1; i < 4; i++) {
                if (!InterpreterHelper.isSTInteger(formPointers[i])) {
                    return false;
                }
            }
            Object bitsObject = formPointers[0];
            width = (Integer) formPointers[1];
            height = (Integer) formPointers[2];
            depth = (Integer) formPointers[3];
            if ((width < 0) || (height < 0)) {
                return false;
            }
            if (bitsObject == nilObj || InterpreterHelper.isSTInteger(bitsObject)) {
                return false;
            }
            msb = depth > 0;
            if (depth < 0) {
                depth = 0 - depth;
            }
            Object maybeBytes = ((SqueakObject) bitsObject).bits;
            if (maybeBytes == null || maybeBytes instanceof byte[]) {
                return false;  //Happens with compressed bits
            }
            bits = (int[]) maybeBytes;
            pixPerWord = 32 / depth;
            pitch = (width + (pixPerWord - 1)) / pixPerWord;
            if (bits.length != (pitch * height)) {
                return false;
            }
            squeakForm = (SqueakObject) aForm; //Only now is it marked as OK
            return true;
        }
    }

    public void setSuccess(boolean suc) {
        this.success = suc;
    }

    public boolean isSuccess() {
        return this.success;
    }

    public void setScreenEvent(boolean screenEvent) {
        this.screenEvent = screenEvent;
    }

    public boolean isScreenEvent() {
        return screenEvent;
    }

    interface BytecodeExcutor {
        void excute(int bytecode);
    }

    final BytecodeExcutor[] bytecodeTable = new BytecodeExcutor[256];

    private final BytecodeExcutor nonoBytecode = (bytecode) -> {
        throw new RuntimeException("Bad bytecode");
    };

    private final BytecodeExcutor pushReceiverVariableBytecode = (bytecode) -> {
        push(
                ((SqueakObject) receiver).getPointer(bytecode & 0xF)
        );
    };

    private final BytecodeExcutor pushTemporaryVariableBytecode = (bytecode) -> {
        push(
                homeContext.getPointer(Squeak.Context_tempFrameStart + (bytecode & 0xF))
        );
    };

    private final BytecodeExcutor pushLiteralConstantBytecode = (bytecode) -> {
        push(
                method.methodGetLiteral(bytecode & 0x1F)
        );
    };

    private final BytecodeExcutor pushLiteralVariableBytecode = (bytecode) -> {
        push(
                ((SqueakObject) method.methodGetLiteral(bytecode & 0x1F))
                        .getPointer(Squeak.Assn_value)
        );
    };

    private final BytecodeExcutor storeAndPopReceiverVariableBytecode = (bytecode) -> {
        ((SqueakObject) receiver).setPointer(bytecode & 7, pop());
    };

    private final BytecodeExcutor storeAndPopTemporaryVariableBytecode = (bytecode) -> {
        homeContext.setPointer(Squeak.Context_tempFrameStart + (bytecode & 7), pop());
    };

    private final BytecodeExcutor pushReceiverBytecode = (bytecode) -> {
        push(receiver);
    };

    private final BytecodeExcutor pushConstantTrueBytecode = (bytecode) -> {
        push(trueObj);
    };

    private final BytecodeExcutor pushConstantFalseBytecode = (bytecode) -> {
        push(falseObj);
    };

    private final BytecodeExcutor pushConstantNilBytecode = (bytecode) -> {
        push(nilObj);
    };

    private final BytecodeExcutor pushConstantMinusOneBytecode = (bytecode) -> {
        push(InterpreterHelper.smallFromInt(-1));
    };

    private final BytecodeExcutor pushConstantZeroBytecode = (bytecode) -> {
        push(InterpreterHelper.smallFromInt(0));
    };

    private final BytecodeExcutor pushConstantOneBytecode = (bytecode) -> {
        push(InterpreterHelper.smallFromInt(1));
    };

    private final BytecodeExcutor pushConstantTwoBytecode = (bytecode) -> {
        push(InterpreterHelper.smallFromInt(2));
    };

    private final BytecodeExcutor returnReceiver = (bytecode) -> {
        doReturn(receiver, homeContext.getPointerNI(Squeak.Context_sender));
    };

    private final BytecodeExcutor returnTrue = (bytecode) -> {
        doReturn(trueObj, homeContext.getPointerNI(Squeak.Context_sender));
    };

    private final BytecodeExcutor returnFalse = (bytecode) -> {
        doReturn(falseObj, homeContext.getPointerNI(Squeak.Context_sender));
    };

    private final BytecodeExcutor returnNil = (bytecode) -> {
        doReturn(nilObj, homeContext.getPointerNI(Squeak.Context_sender));
    };

    private final BytecodeExcutor returnTopFromMethod = (bytecode) -> {
        doReturn(pop(), homeContext.getPointerNI(Squeak.Context_sender));
    };

    private final BytecodeExcutor returnTopFromBlock = (bytecode) -> {
        doReturn(pop(), activeContext.getPointerNI(Squeak.BlockContext_caller));
    };

    private final BytecodeExcutor extendedPushBytecode = (bytecode) -> {
        extendedPush(nextByte());
    };

    private final BytecodeExcutor extendedStoreBytecode = (bytecode) -> {
        extendedStore(nextByte());
    };

    private final BytecodeExcutor extendedStoreAndPopBytecode = (bytecode) -> {
        extendedStorePop(nextByte());
    };

    private final BytecodeExcutor singleExtendedSendBytecode = (bytecode) -> {
        int b2 = nextByte();
        send(method.methodGetSelector(b2 & 31), b2 >> 5, false);
    };

    private final BytecodeExcutor doubleExtendedDoAnythingBytecode = (bytecode) -> {
        doubleExtendedDoAnything(nextByte());
    };

    private final BytecodeExcutor singleExtendedSuperBytecode = (bytecode) -> {
        int b2 = nextByte();
        send(method.methodGetSelector(b2 & 31), b2 >> 5, true);
    };

    private final BytecodeExcutor secondExtendedSendBytecode = (bytecode) -> {
        int b2 = nextByte();
        send(method.methodGetSelector(b2 & 63), b2 >> 6, false);
    };

    private final BytecodeExcutor popStackBytecode = (bytecode) -> {
        pop();
    };

    private final BytecodeExcutor duplicateTopBytecode = (bytecode) -> {
        push(top());
    };

    private final BytecodeExcutor pushActiveContextBytecode = (bytecode) -> {
        push(activeContext);
        reclaimableContextCount = 0;
    };

    private final BytecodeExcutor shortUnconditionalJump = (bytecode) -> {
        pc += (bytecode & 7) + 1;
    };

    private final BytecodeExcutor shortConditionalJump = (bytecode) -> {
        jumpif(false, (bytecode & 7) + 1);
    };

    private final BytecodeExcutor longUnconditionalJump = (bytecode) -> {
        int b2 = nextByte();
        pc += (((bytecode & 7) - 4) * 256 + b2);
        if ((bytecode & 7) < 4) {
            checkForInterrupts();  //check on backward jumps (loops)
        }
    };

    private final BytecodeExcutor longJumpIfTrue = (bytecode) -> {
        jumpif(true, (bytecode & 3) * 256 + nextByte());
    };

    private final BytecodeExcutor longJumpIfFalse = (bytecode) -> {
        jumpif(false, (bytecode & 3) * 256 + nextByte());
    };

    private final BytecodeExcutor bytecodePrimAdd = (bytecode) -> {
        success = true;
        if (!pop2AndPushIntResult(stackInteger(1) + stackInteger(0))) {
            sendSpecial(bytecode & 0xF);
        }
    };

    private final BytecodeExcutor bytecodePrimSubtract = (bytecode) -> {
        success = true;
        if (!pop2AndPushIntResult(stackInteger(1) - stackInteger(0))) {
            sendSpecial(bytecode & 0xF);
        }
    };

    private final BytecodeExcutor bytecodePrimLessThan = (bytecode) -> {
        success = true;
        if (!pushBoolAndPeek(stackInteger(1) < stackInteger(0))) {
            sendSpecial(bytecode & 0xF);
        }
    };

    private final BytecodeExcutor bytecodePrimGreaterThan = (bytecode) -> {
        success = true;
        if (!pushBoolAndPeek(stackInteger(1) > stackInteger(0))) {
            sendSpecial(bytecode & 0xF);
        }
    };

    private final BytecodeExcutor bytecodePrimLessOrEqual = (bytecode) -> {
        success = true;
        if (!pushBoolAndPeek(stackInteger(1) <= stackInteger(0))) {
            sendSpecial(bytecode & 0xF);
        }
    };

    private final BytecodeExcutor bytecodePrimGreaterOrEqual = (bytecode) -> {
        success = true;
        if (!pushBoolAndPeek(stackInteger(1) >= stackInteger(0))) {
            sendSpecial(bytecode & 0xF);
        }
    };

    private final BytecodeExcutor bytecodePrimEqual = (bytecode) -> {
        success = true;
        if (!pushBoolAndPeek(stackInteger(1) == stackInteger(0))) {
            sendSpecial(bytecode & 0xF);
        }
    };

    private final BytecodeExcutor bytecodePrimNotEqual = (bytecode) -> {
        success = true;
        if (!pushBoolAndPeek(stackInteger(1) != stackInteger(0))) {
            sendSpecial(bytecode & 0xF);
        }
    };

    private final BytecodeExcutor bytecodePrimMultiply = (bytecode) -> {
        success = true;
        if (!pop2AndPushIntResult(InterpreterHelper.safeMultiply(stackInteger(1), stackInteger(0)))) {
            sendSpecial(bytecode & 0xF);
        }
    };

    private final BytecodeExcutor bytecodePrimDivide = (bytecode) -> {
        success = true;
        if (!pop2AndPushIntResult(InterpreterHelper.quickDivide(stackInteger(1), stackInteger(0)))) {
            sendSpecial(bytecode & 0xF);
        }
    };

    private final BytecodeExcutor bytecodePrimMod = (bytecode) -> {
        success = true;
        if (!pop2AndPushIntResult(InterpreterHelper.mod(stackInteger(1), stackInteger(0)))) {
            sendSpecial(bytecode & 0xF);
        }
    };

    private final BytecodeExcutor bytecodePrimMakePoint = (bytecode) -> {
        success = true;
        if (!primHandler.primitiveMakePoint()) {
            sendSpecial(bytecode & 0xF);
        }
    };

    private final BytecodeExcutor bytecodePrimBitShift = (bytecode) -> {
        success = true; // Something is wrong with this one...
        // FIXME safeShift
        /*if (!pop2AndPushIntResult(safeShift(stackInteger(1),stackInteger(0))))*/
        sendSpecial(bytecode & 0xF);
    };

    private final BytecodeExcutor bytecodePrimDiv = (bytecode) -> {
        success = true;
        if (!pop2AndPushIntResult(InterpreterHelper.div(stackInteger(1), stackInteger(0)))) {
            sendSpecial(bytecode & 0xF);
        }
    };

    private final BytecodeExcutor bytecodePrimBitAnd = (bytecode) -> {
        success = true;
        if (!pop2AndPushIntResult(stackInteger(1) & stackInteger(0))) {
            sendSpecial(bytecode & 0xF);
        }
    };

    private final BytecodeExcutor bytecodePrimBitOr = (bytecode) -> {
        success = true;
        if (!pop2AndPushIntResult(stackInteger(1) | stackInteger(0))) {
            sendSpecial(bytecode & 0xF);
        }
    };


    private final BytecodeExcutor bytecodePrimAtEtc = (bytecode) -> {
        if (!primHandler.quickSendOther(receiver, bytecode & 0xF)) {
            sendSpecial((bytecode & 0xF) + 16);
        }
    };

    private final BytecodeExcutor sendLiteralSelector0Bytecode = (bytecode) -> {
        send(method.methodGetSelector(bytecode & 0xF), 0, false);
    };

    private final BytecodeExcutor sendLiteralSelector1Bytecode = (bytecode) -> {
        send(method.methodGetSelector(bytecode & 0xF), 1, false);
    };

    private final BytecodeExcutor sendLiteralSelector2Bytecode = (bytecode) -> {
        send(method.methodGetSelector(bytecode & 0xF), 2, false);
    };

    private void initByteCodeTable() {
        // (  0  15 pushReceiverVariableBytecode)
        for (int i = 0; i <= 15; i++) {
            bytecodeTable[i] = pushReceiverVariableBytecode;
        }

        // ( 16  31 pushTemporaryVariableBytecode)
        for (int i = 16; i <= 31; i++) {
            bytecodeTable[i] = pushTemporaryVariableBytecode;
        }

        // ( 32  63 pushLiteralConstantBytecode)
        for (int i = 32; i <= 63; i++) {
            bytecodeTable[i] = pushLiteralConstantBytecode;
        }

        // ( 64  95 pushLiteralVariableBytecode)
        for (int i = 64; i <= 95; i++) {
            bytecodeTable[i] = pushLiteralVariableBytecode;
        }

        // ( 96 103 storeAndPopReceiverVariableBytecode)
        for (int i = 96; i <= 103; i++) {
            bytecodeTable[i] = storeAndPopReceiverVariableBytecode;
        }

        // (104 111 storeAndPopTemporaryVariableBytecode)
        for (int i = 104; i <= 111; i++) {
            bytecodeTable[i] = storeAndPopTemporaryVariableBytecode;
        }

        // (112 pushReceiverBytecode)
        bytecodeTable[112] = pushReceiverBytecode;
        // (113 pushConstantTrueBytecode)
        bytecodeTable[113] = pushConstantTrueBytecode;
        // (114 pushConstantFalseBytecode)
        bytecodeTable[114] = pushConstantFalseBytecode;
        // (115 pushConstantNilBytecode)
        bytecodeTable[115] = pushConstantNilBytecode;
        // (116 pushConstantMinusOneBytecode)
        bytecodeTable[116] = pushConstantMinusOneBytecode;
        // (117 pushConstantZeroBytecode)
        bytecodeTable[117] = pushConstantZeroBytecode;
        // (118 pushConstantOneBytecode)
        bytecodeTable[118] = pushConstantOneBytecode;
        // (119 pushConstantTwoBytecode)
        bytecodeTable[119] = pushConstantTwoBytecode;

        // (120 returnReceiver)
        bytecodeTable[120] = returnReceiver;
        // (121 returnTrue)
        bytecodeTable[121] = returnTrue;
        // (122 returnFalse)
        bytecodeTable[122] = returnFalse;
        // (123 returnNil)
        bytecodeTable[123] = returnNil;

        // (124 returnTopFromMethod)
        bytecodeTable[124] = returnTopFromMethod;
        // (125 returnTopFromBlock)
        bytecodeTable[125] = returnTopFromBlock;

        // (126 unknownBytecode)
        bytecodeTable[126] = nonoBytecode;
        // (127 unknownBytecode)
        bytecodeTable[127] = nonoBytecode;

        // (128 extendedPushBytecode)
        bytecodeTable[128] = extendedPushBytecode;
        // (129 extendedStoreBytecode)
        bytecodeTable[129] = extendedStoreBytecode;
        // (130 extendedStoreAndPopBytecode)
        bytecodeTable[130] = extendedStoreAndPopBytecode;
        // (131 singleExtendedSendBytecode)
        bytecodeTable[131] = singleExtendedSendBytecode;
        // (132 doubleExtendedDoAnythingBytecode)
        bytecodeTable[132] = doubleExtendedDoAnythingBytecode;
        // (133 singleExtendedSuperBytecode)
        bytecodeTable[133] = singleExtendedSuperBytecode;
        // (134 secondExtendedSendBytecode)
        bytecodeTable[134] = secondExtendedSendBytecode;
        // (135 popStackBytecode)
        bytecodeTable[135] = popStackBytecode;
        // (136 duplicateTopBytecode)
        bytecodeTable[136] = duplicateTopBytecode;
        // (137 pushActiveContextBytecode)
        bytecodeTable[137] = pushActiveContextBytecode;

        // (138 143 experimentalBytecode)
        for (int i = 138; i <= 143; i++) {
            bytecodeTable[i] = nonoBytecode;
        }

        // (144 151 shortUnconditionalJump)
        for (int i = 144; i <= 151; i++) {
            bytecodeTable[i] = shortUnconditionalJump;
        }

        // (152 159 shortConditionalJump)
        for (int i = 152; i <= 159; i++) {
            bytecodeTable[i] = shortConditionalJump;
        }

        // (160 167 longUnconditionalJump)
        for (int i = 160; i <= 167; i++) {
            bytecodeTable[i] = longUnconditionalJump;
        }

        // (168 171 longJumpIfTrue)
        for (int i = 168; i <= 171; i++) {
            bytecodeTable[i] = longJumpIfTrue;
        }

        // (172 175 longJumpIfFalse)
        for (int i = 172; i <= 175; i++) {
            bytecodeTable[i] = longJumpIfFalse;
        }

        // "176-191 were sendArithmeticSelectorBytecode"
        // (176 bytecodePrimAdd)
        bytecodeTable[176] = bytecodePrimAdd;
        // (177 bytecodePrimSubtract)
        bytecodeTable[177] = bytecodePrimSubtract;
        // (178 bytecodePrimLessThan)
        bytecodeTable[178] = bytecodePrimLessThan;
        // (179 bytecodePrimGreaterThan)
        bytecodeTable[179] = bytecodePrimGreaterThan;
        // (180 bytecodePrimLessOrEqual)
        bytecodeTable[180] = bytecodePrimLessOrEqual;
        // (181 bytecodePrimGreaterOrEqual)
        bytecodeTable[181] = bytecodePrimGreaterOrEqual;
        // (182 bytecodePrimEqual)
        bytecodeTable[182] = bytecodePrimEqual;
        // (183 bytecodePrimNotEqual)
        bytecodeTable[183] = bytecodePrimNotEqual;
        // (184 bytecodePrimMultiply)
        bytecodeTable[184] = bytecodePrimMultiply;
        // (185 bytecodePrimDivide)
        bytecodeTable[185] = bytecodePrimDivide;
        // (186 bytecodePrimMod)
        bytecodeTable[186] = bytecodePrimMod;
        // (187 bytecodePrimMakePoint)
        bytecodeTable[187] = bytecodePrimMakePoint;
        // (188 bytecodePrimBitShift)
        bytecodeTable[188] = bytecodePrimBitShift;
        // (189 bytecodePrimDiv)
        bytecodeTable[189] = bytecodePrimDiv;
        // (190 bytecodePrimBitAnd)
        bytecodeTable[190] = bytecodePrimBitAnd;
        // (191 bytecodePrimBitOr)
        bytecodeTable[191] = bytecodePrimBitOr;

        // "192-207 were sendCommonSelectorBytecode"
        // (192-207 bytecodePrimAtEtc) use specialSelectors instead of individual impl
        // (192 bytecodePrimAt)
        // (193 bytecodePrimAtPut)
        // (194 bytecodePrimSize)
        // (195 bytecodePrimNext)
        // (196 bytecodePrimNextPut)
        // (197 bytecodePrimAtEnd)
        // (198 bytecodePrimEquivalent)
        // (199 bytecodePrimClass)
        // (200 bytecodePrimBlockCopy)
        // (201 bytecodePrimValue)
        // (202 bytecodePrimValueWithArg)
        // (203 bytecodePrimDo)
        // (204 bytecodePrimNew)
        // (205 bytecodePrimNewWithArg)
        // (206 bytecodePrimPointX)
        // (207 bytecodePrimPointY)
        for (int i = 192; i <= 207; i++) {
            bytecodeTable[i] = bytecodePrimAtEtc;
        }

        // Send Literal Selector with 0, 1, and 2 args

        // (208 223 sendLiteralSelector0Bytecode)
        for (int i = 208; i <= 223; i++) {
            bytecodeTable[i] = sendLiteralSelector0Bytecode;
        }

        // (224 239 sendLiteralSelector1Bytecode)
        for (int i = 224; i <= 239; i++) {
            bytecodeTable[i] = sendLiteralSelector1Bytecode;
        }

        // (240 255 sendLiteralSelector1Bytecode)
        for (int i = 240; i <= 255; i++) {
            bytecodeTable[i] = sendLiteralSelector2Bytecode;
        }

    }
}
