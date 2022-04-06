# Layout

## Special Objects

SqueakV2.sources line 102102
```
initializeSpecialObjectIndices
	"Initialize indices into specialObjects array."

	NilObject _ 0.
	FalseObject _ 1.
	TrueObject _ 2.
	SchedulerAssociation _ 3.
	ClassBitmap _ 4.
	ClassInteger _ 5.
	ClassString _ 6.
	ClassArray _ 7.
	"SmalltalkDictionary _ 8."  "Do not delete!!"
	ClassFloat _ 9.
	ClassMethodContext _ 10.
	ClassBlockContext _ 11.
	ClassPoint _ 12.
	ClassLargePositiveInteger _ 13.
	TheDisplay _ 14.
	ClassMessage _ 15.
	ClassCompiledMethod _ 16.
	TheLowSpaceSemaphore _ 17.
	ClassSemaphore _ 18.
	ClassCharacter _ 19.
	SelectorDoesNotUnderstand _ 20.
	SelectorCannotReturn _ 21.
	TheInputSemaphore _ 22.
	SpecialSelectors _ 23.
	CharacterTable _ 24.
	SelectorMustBeBoolean _ 25.
	ClassByteArray _ 26.
	ClassProcess _ 27.
	CompactClasses _ 28.
	TheTimerSemaphore _ 29.
	TheInterruptSemaphore _ 30.
	SmallMethodContext _ 34.
	SmallBlockContext _ 36.
	ExternalObjectsArray _ 38.
	ClassPseudoContext _ 39.
	ClassTranslatedMethod _ 40.! !
```

| Index | Description |
| :---: | ------- |
| 0 | Nil Object |
| 1 | False Object |
| 2 | True Object |
| 3 | Scheduler Association |
| 4 | Class Bitmap |
| 5 | Class Integer |
| 6 | Class String |
| 7 | Class Array |
| 8 | Smalltalk Dictionary (Deprecated) |
| 9 | Class Float |
| 10 | Class MethodContext |
| 11 | Class BlockContext |
| 12 | Class Point |
| 13 | Class LargePositiveInteger |
| 14 | TheDisplay |
| 15 | Class Message |
| 16 | Class CompiledMethod |
| 17 | TheLowSpaceSemaphore |
| 18 | Class Semaphore |
| 19 | Class Character |
| 20 | Selector DoesNotUnderstand |
| 21 | Selector CannotReturn |
| 22 | TheInputSemaphore |
| 23 | SpecialSelectors |
| 24 | CharacterTable |
| 25 | Selector MustBeBoolean |
| 26 | Class ByteArray |
| 27 | Class Process |
| 28 | CompactClasses |
| 29 | TheTimerSemaphore |
| 30 | TheInterruptSemaphore |
| 34 | SmallMethodContext |
| 36 | SmallBlockContext |
| 38 | ExternalObjectsArray |
| 39 | Class PseudoContext |
| 40 | Class TranslatedMethod |

## Context Layout

SqueakV2.sources line 43747
```
initializeContextIndices
	"Class MethodContext"
	SenderIndex _ 0.
	InstructionPointerIndex _ 1.
	StackPointerIndex _ 2.
	MethodIndex _ 3.
	TranslatedMethodIndex _ 4.
	ReceiverIndex _ 5.
	TempFrameStart _ 6.
	"Class BlockContext"
	CallerIndex _ 0.
	BlockArgumentCountIndex _ 3.
	InitialIPIndex _ 4.
	HomeIndex _ 5! !
```

### Method Context

| Index | Description |
| :---: | ------- |
| 0 | Sender |
| 1 | Instruction Pointer |
| 2 | Stack Pointer |
| 3 | Method |
| 4 | Translated Method |
| 5 | Receiver |
| 6 | Temp Frame Start |

### Block Context

| Index | Description |
| :---: | ------- |
| 0 | Caller |
| 3 | Block Argument Count |
| 4 | Initial IP |
| 5 | Home |
