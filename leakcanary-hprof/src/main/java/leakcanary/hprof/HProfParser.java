package leakcanary.hprof;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.text.NumberFormat;
import java.util.*;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.filechooser.FileFilter;

import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;

public class HProfParser {
  // HPROF tags
  public static final byte UTF8_STRING = 0x01;
  public static final byte LOAD_CLASS = 0x02;
  public static final byte UNLOAD_CLASS = 0x03;
  public static final byte STACK_FRAME = 0x04;
  public static final byte STACK_TRACE = 0x05;
  public static final byte ALLOC_SITES = 0x06;
  public static final byte HEAP_SUMMARY = 0x07;
  public static final byte START_THREAD = 0x0A;
  public static final byte END_THREAD = 0x0B;
  public static final byte HEAP_DUMP = 0x0C;
  public static final byte HEAP_DUMP_SEGMENT = 0x1C;
  public static final byte HEAP_DUMP_END = 0x2C;
  public static final byte CPU_SAMPLES = 0x0D;
  public static final byte CONTROL_SETTINGS = 0x0E;

  // HPROF sub-tags for HEAP_DUMP & HEAP_DUMP_SEGMENT tags
  public static final byte ROOT_UNKNOWN = -1;            // 0xFF
  public static final byte ROOT_JNI_GLOBAL = 0x01;
  public static final byte ROOT_JNI_LOCAL = 0x02;
  public static final byte ROOT_JAVA_FRAME = 0x03;
  public static final byte ROOT_NATIVE_STACK = 0x04;
  public static final byte ROOT_STICKY_CLASS = 0x05;
  public static final byte ROOT_MONITOR_USED = 0x07;
  public static final byte ROOT_THREAD_OBJECT = 0x08;
  public static final byte CLASS_DUMP = 0x20;
  public static final byte INSTANCE_DUMP = 0x21;
  public static final byte OBJECT_ARRAY_DUMP = 0x22;
  public static final byte PRIMITIVE_ARRAY_DUMP = 0x23;

  // HPROF tags for Android = HPROF version 1.3
  public static final byte HEAP_DUMP_INFO = -2;          // 0xFE
  public static final byte ROOT_INTERNED_STRING = -119;  // 0x89
  public static final byte ROOT_FINALIZING = -118;       // 0x8A
  public static final byte ROOT_DEBUGGER = -117;         // 0x8B

  private static final boolean DEBUG = true;
  private static final boolean LOG_STRINGS = false;
  private static final boolean LOG_CLASS_LOADS = false;
  private static final boolean LOG_ROOTS = false;
  private static final boolean LOG_HEAP_INFO = true;
  private static final boolean LOG_INSTANCES = true;


  private static final NumberFormat percentFormatter;

  static {
    percentFormatter = NumberFormat.getPercentInstance(Locale.getDefault());
    percentFormatter.setMinimumFractionDigits(2);
  }

  private static int UTF8_STRING_COUNT = 0;
  private static int LOAD_CLASS_COUNT = 0;
  private static int UNLOAD_CLASS_COUNT = 0;
  private static int STACK_FRAME_COUNT = 0;
  private static int STACK_TRACE_COUNT = 0;
  private static int ALLOC_SITES_COUNT = 0;
  private static int HEAP_SUMMARY_COUNT = 0;
  private static int START_THREAD_COUNT = 0;
  private static int END_THREAD_COUNT = 0;
  private static int HEAP_DUMP_COUNT = 0;
  private static int HEAP_DUMP_SEGMENT_COUNT = 0;
  private static int HEAP_DUMP_END_COUNT = 0;
  private static int CPU_SAMPLES_COUNT = 0;
  private static int CONTROL_SETTINGS_COUNT = 0;

  private static int ROOT_UNKNOWN_COUNT = 0;
  private static int ROOT_JNI_GLOBAL_COUNT = 0;
  private static int ROOT_JNI_LOCAL_COUNT = 0;
  private static int ROOT_JAVA_FRAME_COUNT = 0;
  private static int ROOT_NATIVE_STACK_COUNT = 0;
  private static int ROOT_STICKY_CLASS_COUNT = 0;
  private static int ROOT_MONITOR_USED_COUNT = 0;
  private static int ROOT_THREAD_OBJECT_COUNT = 0;
  private static int CLASS_COUNT = 0;
  private static int INSTANCE_COUNT = 0;
  private static int OBJECT_ARRAY_COUNT = 0;
  private static int PRIMITIVE_ARRAY_COUNT = 0;

  private static int HEAP_DUMP_INFO_COUNT = 0;
  private static int ROOT_INTERNED_STRING_COUNT = 0;
  private static int ROOT_FINALIZING_COUNT = 0;
  private static int ROOT_DEBUGGER_COUNT = 0;

  private String format;
  private int idSize;
  private long startTime;

  private int recordCount = 0;
  private long numBytesRead = 0;
  private long numStringBytesRead = 0;
  private boolean hasDumpedStrings = true;
  private File heapFile;

  private Map<Long, String> stringMap = new HashMap<>();
  private Map<Long, Long> classMap = new HashMap<>();
  private Map<Integer, Long> classSerialMap = new HashMap<>();
  private Map<Integer, Long> stackFrameMap = new HashMap<>();
  private Map<Long, Instance> instanceMap = new HashMap<>();
  private Set<Long> primitiveArraySet = new HashSet<>();
  private List<Instance> instances = new ArrayList<>();
  private Map<Long, ClassInfo> classInfoMap = new HashMap<>();

  private static long OBJECT_CLASS_ID = 0;

  public static void main(String[] args) throws IOException {
    // File heapFile = chooseFileFromGUI();
    File heapFile = new File(args[0]);
    HProfParser parser = new HProfParser();
    parser.parse(heapFile);

    //    Scanner stdin = new Scanner(System.in);
    //
    //    while(true) {
    //      System.out.println("What do you want to do?");
    //      System.out.println("a) Search for a class");
    //      System.out.println("b) Search for an instance");
    //      System.out.println();
    //      System.out.print("$ ");
    //
    //      while (stdin.hasNextLine()) {
    //        String input = stdin.nextLine();
    //        if (input.equals("exit")) break;
    //
    //        System.out.println(input);
    //      }
    //    }
  }

  private static File chooseFileFromGUI() {
    JFileChooser fileChooser = new JFileChooser();
    fileChooser.setFileFilter(new FileFilter() {
      @Override
      public boolean accept(File f) {
        return f.getName().endsWith(".hprof");
      }

      @Override
      public String getDescription() {
        return "HPROF files";
      }
    });
    int retValue = fileChooser.showOpenDialog(new JPanel());
    if (retValue != JFileChooser.APPROVE_OPTION) {
      System.out.println("Next time select a file.");
      System.exit(1);
    }
    return fileChooser.getSelectedFile();
  }

  private void parse(File heapFile) throws IOException {
    this.heapFile = heapFile;

    BufferedSource hprofSource = Okio.buffer(Okio.source(heapFile));

    long start = System.currentTimeMillis();
    parseHeader(hprofSource);
    parseRecords(hprofSource);
    long end = System.currentTimeMillis();
    log("Total parse time", (end - start) + "ms");

    assembleInstances();

    logTotals();

    if (heapFile.length() != numBytesRead) {
      String detailMessage = String.format("heap length (%d) != # bytes read (%d)", heapFile.length(), numBytesRead);
      throw new AssertionError(detailMessage);
    } else {
      System.out.println("Assert check pass!  heap length == # bytes read");
      System.out.println("");
    }
  }

  private void assembleInstances() throws IOException {
    log("INSTANCES");
    for (Instance instance: instances) {
      BufferedSource source = Okio.buffer(Okio.source(new ByteArrayInputStream(instance.fieldBytes)));
      long classId = instance.classId;
      int classIndent = 1;
      while(classId != OBJECT_CLASS_ID) {
        log(classIndent, getClassName(classId));
        ClassInfo classInfo = classInfoMap.get(classId);
        for (InstanceField field: classInfo.instanceFields) {
          String fieldName = stringMap.get(field.stringId);
          Object fieldValue = readTypeElement(source, field.type);

          String type = null;
          if (field.type == Type.OBJECT) {
            // ?
            //field.stringId
            getStringName(instanceMap.get(fieldValue).classId);
          }
          else {
            type = field.type.name;
          }
          log(classIndent + 1, String.format("%s %s", type, fieldName), fieldValue);
        }
        classId = classInfo.superClassObjectID;
        classIndent++;
      }
    }
  }

  private void logTotals() {
    log("======================");

    log("UTF8 String count", UTF8_STRING_COUNT);
    log("Load Class count", LOAD_CLASS_COUNT);
    log("Unload Class count", UNLOAD_CLASS_COUNT);
    log("Stack Frame count", STACK_FRAME_COUNT);
    log("Stack Trace count", STACK_TRACE_COUNT);
    log("Alloc Sites count", ALLOC_SITES_COUNT);
    log("Heap Summary count", HEAP_SUMMARY_COUNT);
    log("Start Thread count", START_THREAD_COUNT);
    log("End Thread count", END_THREAD_COUNT);
    log("Heap Dump count", HEAP_DUMP_COUNT);
    log("Heap Dump Segment count", HEAP_DUMP_SEGMENT_COUNT);
    log("Heap Dump End count", HEAP_DUMP_END_COUNT);
    log("CPU Samples count", CPU_SAMPLES_COUNT);
    log("Control Settings count", CONTROL_SETTINGS_COUNT);

    log("Root Unknown count", ROOT_UNKNOWN_COUNT);
    log("Root JNI Global count", ROOT_JNI_GLOBAL_COUNT);
    log("Root JNI Local count", ROOT_JNI_LOCAL_COUNT);
    log("Root Java Frame count", ROOT_JAVA_FRAME_COUNT);
    log("Root Native Stack count", ROOT_NATIVE_STACK_COUNT);
    log("Root Sticky Class count", ROOT_STICKY_CLASS_COUNT);
    log("Root Monitor Used count", ROOT_MONITOR_USED_COUNT);
    log("Root Thread Object count", ROOT_THREAD_OBJECT_COUNT);
    log("Class count", CLASS_COUNT);
    log("Instance count", INSTANCE_COUNT);
    log("Object Array count", OBJECT_ARRAY_COUNT);
    log("Primitive Array count", PRIMITIVE_ARRAY_COUNT);

    log("Heap Dump Info count", HEAP_DUMP_INFO_COUNT);
    log("Root Interned String count", ROOT_INTERNED_STRING_COUNT);
    log("Root Finalizing count", ROOT_FINALIZING_COUNT);
    log("Root Debugger count", ROOT_DEBUGGER_COUNT);

    log("======================");
  }

  private void parseHeader(BufferedSource hprofSource) throws IOException {
    format = readUntilNull(hprofSource);

    idSize = hprofSource.readInt();
    startTime = hprofSource.readLong();
    // readUntilNull adds to numBytesRead as well
    numBytesRead += 4 + 8;

    log("HEADER");
    log(1, "format", format);
    log(1, "ID size", idSize);
    log(1, "start date", new Date(startTime));
  }

  private void parseRecords(BufferedSource hprofSource) throws IOException {
    log("RECORDS");

    while (!hprofSource.exhausted()) {
      recordCount++;

      byte tag = hprofSource.readByte();
      int time = hprofSource.readInt();
      int bodySize = hprofSource.readInt();
      numBytesRead += 9;

      if (tag != 0x01 && !hasDumpedStrings) {
        hasDumpedStrings = true;
        System.out.println("# bytes read: " + numBytesRead);
        System.out.println("# string bytes read: " + numStringBytesRead);
        double stringRecordPct = (numStringBytesRead * 1.0) / heapFile.length();
        System.out.println("% string records: " + percentFormatter.format(stringRecordPct));
        System.out.println("# strings: " + stringMap.size());
        stringMap.entrySet().forEach(System.out::println);
        System.exit(0);
      }

      if (shouldLogTag(tag)) {
        logRecord(tag, time, bodySize);
      }

      numBytesRead += bodySize;

      switch (tag) {
        case UTF8_STRING: {
          UTF8_STRING_COUNT++;

          long id = readId(hprofSource);
          bodySize -= idSize;
          String string = hprofSource.readUtf8(bodySize);
          stringMap.put(id, string);
          numStringBytesRead = numStringBytesRead + idSize + bodySize;
          break;
        }
        case LOAD_CLASS: {
          LOAD_CLASS_COUNT++;

          // class serial numbers only used in stack traces and alloc sites
          // consider removing?
          int classSerialNumber = readSerialNumber(hprofSource);
          long classObjectId = readId(hprofSource);
          readSerialNumber(hprofSource); // stack trace serial #
          long classNameStringId = readId(hprofSource);
          classMap.put(classObjectId, classNameStringId);
          classSerialMap.put(classSerialNumber, classNameStringId);

          if (bodySize != 8 + 2 * idSize) throw new AssertionError();

          if (LOG_CLASS_LOADS) {
            log(2, "LOAD CLASS", stringMap.get(classNameStringId));
            //          log(3, "class serial #", classSerialNumber);
            //          log(3, "class object id", classObjectId);
            //          log(3, "class", stringMap.get(classNameStringId));
          }

          if (OBJECT_CLASS_ID == 0) {
            String s = stringMap.get(classNameStringId);
            if(s.equals("java.lang.Object")) {
              OBJECT_CLASS_ID = classObjectId;
            }
          }

          break;
        }
        case UNLOAD_CLASS: {
          UNLOAD_CLASS_COUNT++;

          if (bodySize != 4) throw new AssertionError();

          int classSerialNumber = readSerialNumber(hprofSource);

          log(2, "UNLOAD CLASS");
          log(3, "class serial #", classSerialNumber);

          break;
        }
        case STACK_FRAME: {
          STACK_FRAME_COUNT++;

          if (bodySize != 8 + 4 * idSize) throw new AssertionError();

          long stackFrameId = readId(hprofSource);
          long methodNameStringId = readId(hprofSource);
          long methodSignatureStringId = readId(hprofSource);
          long sourceFileNameStringId = readId(hprofSource);
          int classSerialNumber = readSerialNumber(hprofSource);
          String lineNumberInfo = readLineNumber(hprofSource);

          if (false) {
            log(2, "STACK FRAME");
            log(3, "stack frame id", stackFrameId);
            log(3, "method name", stringMap.get(methodNameStringId));
            log(3, "method signature", stringMap.get(methodSignatureStringId));
            log(3, "source filename", stringMap.get(sourceFileNameStringId));
            log(3, "class serial #", getClassName(classSerialNumber));
            log(3, "line number", lineNumberInfo);
          }

          break;
        }
        case STACK_TRACE: {
          STACK_TRACE_COUNT++;

          readSerialNumber(hprofSource); // stack trace serial #
          int threadSerialNumber = readSerialNumber(hprofSource);
          int numFrames = hprofSource.readInt();

          if (bodySize != 12 + idSize * numFrames) throw new AssertionError();

          List<Long> stackFrameIds = new ArrayList<>(numFrames);
          for (int i = 0; i < numFrames; i++) {
            stackFrameIds.add(readId(hprofSource));
          }

          if (numFrames == 0 && false) {
            log(2, "STACK TRACE", "");
            log(3, "thread serial #", getClassName(threadSerialNumber));
            log(3, "frames", stackFrameIds);
          }

          break;
        }
        case ALLOC_SITES: // ALLOC SITES
        {
          ALLOC_SITES_COUNT++;

          short bitMaskFlags = hprofSource.readShort();
          float cutoffRatio = (float) hprofSource.readInt();
          int totalLiveBytes = hprofSource.readInt();
          int totalLiveInstances = hprofSource.readInt();
          long totalBytesAllocated = hprofSource.readLong();
          long totalInstancesAllocated = hprofSource.readLong();
          int numSites = hprofSource.readInt();

          log(2, "ALLOC SITES", "");
          log(3, "flags", Integer.toBinaryString(bitMaskFlags));
          log(3, "cutoff ratio", cutoffRatio);
          log(3, "total live bytes", totalLiveBytes);
          log(3, "total live instances", totalLiveInstances);
          log(3, "total bytes allocated", totalBytesAllocated);
          log(3, "total instances allocated", totalInstancesAllocated);
          log(3, "num sites", numSites);

          for (int i = 0; i < numSites; i++) {
            byte arrayIndicator = hprofSource.readByte();
            int classSerialNumber = readSerialNumber(hprofSource);
            readSerialNumber(hprofSource); //stack trace serial #
            int numLiveBytes = hprofSource.readInt();
            int numLiveInstances = hprofSource.readInt();
            int numBytesAllocated = hprofSource.readInt();
            int numInstancesAllocated = hprofSource.readInt();

            log(4, "array indicator", arrayIndicator);
            log(4, "class serial #", classSerialNumber);
            log(4, "# live bytes", numLiveBytes);
            log(4, "# live instances", numLiveInstances);
            log(4, "# bytes allocated", numBytesAllocated);
            log(4, "# instances allocated", numInstancesAllocated);
          }
          break;
        }
        case HEAP_SUMMARY: // HEAP SUMMARY
        {
          HEAP_SUMMARY_COUNT++;

          log(2, "HEAP SUMMARY");
          int totalLiveBytes = hprofSource.readInt();
          int totalLiveInstances = hprofSource.readInt();
          long totalBytesAllocated = hprofSource.readLong();
          long totalInstancesAllocated = hprofSource.readLong();
          log(3, "total live bytes", totalLiveBytes);
          log(3, "total live instances", totalLiveInstances);
          log(3, "total bytes allocated", totalBytesAllocated);
          log(3, "total instances allocated", totalInstancesAllocated);
          break;
        }
        case START_THREAD: {
          START_THREAD_COUNT++;

          log(2, "HEAP_SUMMARY");
          throw new RuntimeException("not yet implemented");
        }
        case HEAP_DUMP: {
          HEAP_DUMP_COUNT++;

          log(2, "HEAP DUMP");
          parseHeapDump(hprofSource, bodySize);
          processInstances();
          break;
        }
        case HEAP_DUMP_SEGMENT: // HEAP DUMP SEGMENT
        {
          HEAP_DUMP_SEGMENT_COUNT++;

          log(2, "HEAP DUMP SEGMENT");
          parseHeapDump(hprofSource, bodySize);
          break;
        }
        case HEAP_DUMP_END: // HEAP DUMP END
        {
          HEAP_DUMP_END_COUNT++;

          log(2, "HEAP DUMP END");
          break;
        }

        default:
          throw new IllegalArgumentException("Unexpected top-level record type: " + tag);
      }
    }
  }

  private boolean shouldLogTag(byte tag) {
    return false;
    //return tag < UTF8_STRING || tag >= STACK_TRACE || tag == LOAD_CLASS;
  }

  private void processInstances() {
    for (Instance instance : instances) {
      long superClassId = instance.classId;
      while (superClassId != 0) {
        ClassInfo classInfo = classInfoMap.get(superClassId);
        superClassId = classInfo.superClassObjectID;
        for (InstanceField field : classInfo.instanceFields) {
          switch (field.type) {
            case OBJECT:
          }
        }
      }
    }
  }

  private void logRecord(byte tag, int time, int bodySize) {
    log(1, "record #" + recordCount);
    log(2, "tag", tag);
    log(2, "time", time);
    log(2, "body size", bodySize);
  }

  private void parseHeapDump(BufferedSource hprofSource, int numBytes) throws IOException {
    while (numBytes > 0) {
      byte tag = hprofSource.readByte();
      numBytes--;
      switch (tag) {
        case ROOT_UNKNOWN: {
          ROOT_UNKNOWN_COUNT++;

          long objectId = readId(hprofSource);
          numBytes -= idSize;

          log(3, "tag", "ROOT UNKNOWN");
          log(4, "object ID", getClassName(objectId));

          break;
        }
        case ROOT_JNI_GLOBAL: {
          ROOT_JNI_GLOBAL_COUNT++;

          long objectId = readId(hprofSource);
          long jniGlobalRefId = readId(hprofSource);
          numBytes -= 2 * idSize;

          if (LOG_ROOTS) {
            log(3, "tag", "ROOT JNI GLOBAL");
            log(4, "object ID", getClassName(objectId));
            //log(4, "jni global ref id", jniGlobalRefId);
          }
          break;
        }
        case ROOT_JNI_LOCAL: {
          ROOT_JNI_LOCAL_COUNT++;

          long objectId = readId(hprofSource);
          int threadSerialNumber = readSerialNumber(hprofSource);
          int stackTraceFrameNumber = hprofSource.readInt();
          numBytes -= 8 + idSize;

          if (LOG_ROOTS) {
            log(3, "tag", "ROOT JNI LOCAL");
            log(4, "object ID", getClassName(objectId));
            // log(4, "thread serial #", threadSerialNumber);
            // log(4, "stack trace frame #", stackTraceFrameNumber);
          }
          break;
        }
        case ROOT_JAVA_FRAME: {
          ROOT_JAVA_FRAME_COUNT++;

          long objectId = readId(hprofSource);
          int threadSerialNumber = readSerialNumber(hprofSource);
          int stackTraceFrameNumber = hprofSource.readInt();
          numBytes -= 8 + idSize;

          if (LOG_ROOTS) {
            log(3, "tag", "ROOT JAVA FRAME");
            // log(4, "object ID", getClassName(objectId));
            // log(4, "thread serial #", threadSerialNumber);
            // log(4, "stack trace frame #", stackTraceFrameNumber);
          }
          break;
        }
        case ROOT_NATIVE_STACK: {
          ROOT_NATIVE_STACK_COUNT++;

          long objectId = readId(hprofSource);
          int threadSerialNumber = readSerialNumber(hprofSource);
          numBytes -= 4 + idSize;

          if (LOG_ROOTS) {
            log(3, "tag", "ROOT NATIVE STACK");
            log(4, "object ID", objectId);
            log(4, "thread serial #", threadSerialNumber);
          }

          break;
        }
        case ROOT_STICKY_CLASS: {
          ROOT_STICKY_CLASS_COUNT++;

          long objectId = readId(hprofSource);
          numBytes -= idSize;

          if (LOG_ROOTS) {
            log(3, "tag", "ROOT STICKY CLASS");
            log(4, "object ID", getClassName(objectId));
          }

          break;
        }
        case ROOT_MONITOR_USED: {
          ROOT_MONITOR_USED_COUNT++;

          long objectId = readId(hprofSource);
          numBytes -= idSize;

          log(3, "tag", "ROOT MONITOR USED");
          log(4, "object ID", getClassName(objectId));

          break;
        }
        case ROOT_THREAD_OBJECT: {
          ROOT_THREAD_OBJECT_COUNT++;

          long threadObjectId = readId(hprofSource);
          int threadSerialNumber = readSerialNumber(hprofSource);
          readSerialNumber(hprofSource); // stack trace serial #
          numBytes -= 8 + idSize;

          if (LOG_ROOTS) {
            log(3, "tag", "ROOT THREAD OBJECT");
            log(4, "thread object ID", threadObjectId);
            log(4, "thread serial #", threadSerialNumber);
          }
          break;
        }
        case CLASS_DUMP: {
          CLASS_COUNT++;

          long classObjectId = readId(hprofSource);
          readSerialNumber(hprofSource); // stack trace serial #
          long superClassObjectID = readId(hprofSource);
          long classLoaderObjectID = readId(hprofSource);
          readId(hprofSource); // signers object ID
          readId(hprofSource); // protection domain object ID
          readId(hprofSource); // reserved
          readId(hprofSource); // reserved
          int instanceSize = hprofSource.readInt();
          numBytes -= 7 * idSize + 8;

          log(3, "tag", "CLASS DUMP");
          log(4, "class", getClassName(classObjectId));
          log(4, "super class", getClassName(superClassObjectID));
          // log(4, "class loader", getStringName(classLoaderObjectID));
          log(4, "instance size", instanceSize);

          short constantPoolSize = hprofSource.readShort();
          numBytes -= 2;

          if (constantPoolSize != 0) {
            log(4, "constants", "");

            for (int i = 0; i < constantPoolSize; i++) {
              short constantPoolIndex = hprofSource.readShort();
              Type type = Type.getType(hprofSource.readByte());
              Object value = readTypeElement(hprofSource, type);
              numBytes -= 3 + getTypeSize(type);
              log(5, "#" + constantPoolIndex, type + " -> " + value);
            }
          }

          short numStaticFields = hprofSource.readShort();
          numBytes -= 2;

          if (numStaticFields != 0) {
            log(4, "static fields", "");

            for (int i = 0; i < numStaticFields; i++) {
              long staticFieldNameStringID = readId(hprofSource);
              Type type = Type.getType(hprofSource.readByte());
              Object value = readTypeElement(hprofSource, type);
              numBytes -= idSize + 1 + getTypeSize(type);
              log(5, type + " " + stringMap.get(staticFieldNameStringID), value);
            }
          }

          short numInstanceFields = hprofSource.readShort();
          numBytes -= 2;

          InstanceField[] instanceFields = new InstanceField[numInstanceFields];
          if (numInstanceFields != 0) {
            log(4, "instance fields", "");
            for (int i = 0; i < numInstanceFields; i++) {
              long fieldNameStringID = readId(hprofSource);
              Type type = Type.getType(hprofSource.readByte());
              instanceFields[i] = new InstanceField(fieldNameStringID, type);
              numBytes -= idSize + 1;
              log(5, type + " " + stringMap.get(fieldNameStringID), "");
            }
          }
          ClassInfo classInfo = new ClassInfo(classObjectId, superClassObjectID, instanceSize, instanceFields);
          classInfoMap.put(classObjectId, classInfo);

          break;
        }
        case INSTANCE_DUMP: {
          INSTANCE_COUNT++;

          long objectId = readId(hprofSource);
          hprofSource.readInt(); // stack trace serial #
          long classObjectId = readId(hprofSource);
          int numInstanceBytes = hprofSource.readInt();

          // TODO - process instance values afterward
          byte[] instanceFieldValueBytes = hprofSource.readByteArray(numInstanceBytes);
          Instance instance = new Instance(objectId, classObjectId, instanceFieldValueBytes);
          instances.add(instance);

          numBytes -= 2 * idSize + 8 + numInstanceBytes;

          log(3, "tag", "INSTANCE DUMP");
          log(4, "object id", objectId);
          log(4, "class", getClassName(classObjectId));
          log(4, "num instance bytes", numInstanceBytes);

          instanceMap.put(objectId, instance);

          break;
        }
        case OBJECT_ARRAY_DUMP: {
          OBJECT_ARRAY_COUNT++;

          long arrayObjectId = readId(hprofSource);
          readSerialNumber(hprofSource); // stack trace serial #
          int numElements = hprofSource.readInt();
          long arrayClassObjectId = readId(hprofSource);
          List<?> elements = readTypeElements(hprofSource, Type.OBJECT, numElements);

          numBytes -= 8 + (numElements + 2) * idSize;

          log(3, "tag", "OBJECT ARRAY DUMP");
          log(4, "array object id", arrayObjectId);
          log(4, "array class", getClassName(arrayClassObjectId));
          log(4, "# elements", numElements);

          break;
        }
        case PRIMITIVE_ARRAY_DUMP: {
          PRIMITIVE_ARRAY_COUNT++;

          long arrayObjectId = readId(hprofSource);
          readSerialNumber(hprofSource); // stack trace serial #
          int numElements = hprofSource.readInt();
          Type type = Type.getType(hprofSource.readByte());
          List<?> elements = readTypeElements(hprofSource, type, numElements);
          numBytes -= idSize + 9 + numElements * getTypeSize(type);

          if (numElements != 0) {
            log(3, "tag", "PRIMITIVE ARRAY DUMP");
            log(4, "array object id", arrayObjectId);
            //log(4, "# elements", numElements);
            if (type != Type.CHAR) {
              log(4, String.format("%s[%d]", type, numElements),
                  elements.size() > 30 ? elements.subList(0, 30) : elements);
            } else {
              log(4, String.format("%s[%d]", type, numElements),
                  "\"" +
                      elements.stream().map(Object::toString).reduce((acc, e) -> acc + e).get() +
                      "\""
              );
            }
          }

          primitiveArraySet.add(arrayObjectId);

          break;
        }
        case HEAP_DUMP_INFO: {
          HEAP_DUMP_INFO_COUNT++;

          long objectId = readId(hprofSource);
          long heapNameStringId = readId(hprofSource);
          numBytes -= 2 * idSize;

          log(3, "tag", "HEAP DUMP INFO");
          log(4, "object ID", objectId);
          log(4, "heap name", getStringName(heapNameStringId));

          break;
        }
        case ROOT_INTERNED_STRING: {
          ROOT_INTERNED_STRING_COUNT++;

          long objectId = readId(hprofSource);
          numBytes -= idSize;

          log(3, "tag", "ROOT INTERNED STRING");
          log(4, "object ID", stringMap.get(objectId));

          break;
        }
        case ROOT_FINALIZING: {
          ROOT_FINALIZING_COUNT++;

          long objectId = readId(hprofSource);
          numBytes -= idSize;

          log(3, "tag", "ROOT FINALIZING");
          log(4, "object ID", objectId);

          break;
        }
        case ROOT_DEBUGGER: {
          ROOT_DEBUGGER_COUNT++;

          long objectId = readId(hprofSource);
          numBytes -= idSize;

          if (LOG_ROOTS) {
            log(3, "tag", "ROOT DEBUGGER");
            // log(4, "object ID", objectId);
          }

          break;
        }
        default:
          log(0, "numBytes", numBytes);
          throw new IllegalArgumentException("Unexpected heap dump record type: " + tag);
      }
    }
  }

  private String getStringName(long stringId) {
    String stringName = stringMap.get(stringId);
    return stringName == null ? "???" : stringName;
  }

  private String getClassName(long classObjectId) {
    Long classStringId = classMap.get(classObjectId);
    if (classStringId == null) return "???";
    return getStringName(classStringId);
  }

  private String getClassName(int classSerialId) {
    Long classStringId = classSerialMap.get(classSerialId);
    if (classStringId == null) return "???";
    return getStringName(classStringId);
  }

  private int getTypeSize(Type type) {
    return type == Type.OBJECT ? idSize : type.numBytes;
  }

  private Object readTypeElement(BufferedSource hprofSource, Type type) throws IOException {
    switch (type) {
      case OBJECT:
        return readId(hprofSource);
      case BOOLEAN:
        return hprofSource.readByte() == 0;
      case CHAR:
        return hprofSource.readUtf8(2).charAt(0);
      case FLOAT:
        return (float) hprofSource.readInt();
      case DOUBLE:
        return (double) hprofSource.readLong();
      case BYTE:
        return hprofSource.readByte();
      case SHORT:
        return hprofSource.readShort();
      case INT:
        return hprofSource.readInt();
      case LONG:
        return hprofSource.readLong();
      default:
        throw new IllegalStateException("illegal type; this should never happen");
    }
  }

  private List<?> readTypeElements(BufferedSource hprofSource, Type type, int numElements) throws IOException {
    switch (type) {
      case OBJECT: {
        Long[] a = new Long[numElements];
        for (int i = 0; i < numElements; i++) {
          a[i] = readId(hprofSource);
        }
        return Arrays.asList(a);
      }
      case BOOLEAN: {
        Boolean[] a = new Boolean[numElements];
        for (int i = 0; i < numElements; i++) {
          a[i] = hprofSource.readByte() == 0;
        }
        return Arrays.asList(a);
      }
      case CHAR: {
        Character[] a = new Character[numElements];
        for (int i = 0; i < numElements; i++) {
          String s = hprofSource.readString(2, Charset.forName("UTF-16BE"));
          a[i] = s.charAt(0);
        }
        return Arrays.asList(a);
      }
      case FLOAT: {
        Float[] a = new Float[numElements];
        for (int i = 0; i < numElements; i++) {
          a[i] = (float) hprofSource.readInt();
        }
        return Arrays.asList(a);
      }
      case DOUBLE: {
        Double[] a = new Double[numElements];
        for (int i = 0; i < numElements; i++) {
          a[i] = (double) hprofSource.readLong();
        }
        return Arrays.asList(a);
      }
      case BYTE: {
        Byte[] a = new Byte[numElements];
        for (int i = 0; i < numElements; i++) {
          a[i] = hprofSource.readByte();
        }
        return Arrays.asList(a);
      }
      case SHORT: {
        Short[] a = new Short[numElements];
        for (int i = 0; i < numElements; i++) {
          a[i] = hprofSource.readShort();
        }
        return Arrays.asList(a);
      }
      case INT: {
        Integer[] a = new Integer[numElements];
        for (int i = 0; i < numElements; i++) {
          a[i] = hprofSource.readInt();
        }
        return Arrays.asList(a);
      }
      case LONG: {
        Long[] a = new Long[numElements];
        for (int i = 0; i < numElements; i++) {
          a[i] = hprofSource.readLong();
        }
        return Arrays.asList(a);
      }
      default:
        throw new IllegalStateException("this can't happen");
    }
  }

  public enum Type {
    OBJECT(null, -1), // variable
    BOOLEAN("boolean", 1),
    CHAR("char", 2),
    FLOAT("float", 4),
    DOUBLE("double", 8),
    BYTE("byte", 1),
    SHORT("short", 2),
    INT("int", 4),
    LONG("long", 8);

    public final String name;
    public final int numBytes;

    Type(String name, int numBytes) {
      this.name = name;
      this.numBytes = numBytes;
    }

    public static Type getType(byte type) {
      switch (type) {
        case 2:
          return OBJECT;
        case 4:
          return BOOLEAN;
        case 5:
          return CHAR;
        case 6:
          return FLOAT;
        case 7:
          return DOUBLE;
        case 8:
          return BYTE;
        case 9:
          return SHORT;
        case 10:
          return INT;
        case 11:
          return LONG;
        default:
          throw new IllegalArgumentException("Unexpected type in heap dump: " + type);
      }
    }
  }

  private String readLineNumber(BufferedSource hprofSource) throws IOException {
    int lineNumber = hprofSource.readInt();
    switch (lineNumber) {
      case 0:
        return "no line information available";
      case -1:
        return "unknown location";
      case -2:
        return "compiled method";
      case -3:
        return "native method";
      default:
        return Integer.toString(lineNumber);
    }
  }

  private int readSerialNumber(BufferedSource hprofSource) throws IOException {
    int serialNumber = hprofSource.readInt();
    //if (serialNumber <= 0) throw new IllegalStateException("serial number always > 0");
    return serialNumber;
  }

  private long readId(BufferedSource hprofSource) throws IOException {
    switch (idSize) {
      case 4:
        long id = hprofSource.readInt();
        return id & 0x00000000ffffffffL;
      case 8:
        return hprofSource.readLong();
      default:
        throw new IllegalArgumentException("Invalid identifier size: " + idSize);
    }
  }

  private String readUntilNull(BufferedSource hprofSource) throws IOException {
    Buffer buffer = new Buffer();
    byte b;
    while ((b = hprofSource.readByte()) != 0) {
      buffer.writeByte(b);
      numBytesRead++;
    }
    numBytesRead++; // null byte

    return buffer.readUtf8();
  }

  private static StringBuilder builder = new StringBuilder();

  private void log(String label) {
    log(0, label, null);
  }

  private void log(String label, Object value) {
    log(0, label, value);
  }

  private void log(int numTabs, String label) {
    log(numTabs, label, null);
  }

  private void log(int numTabs, String label, Object value) {
    if (!DEBUG) return;
    if (value instanceof Integer && (Integer) value == 0) return;

    builder.setLength(0);
    for (int i = 0; i < numTabs; i++) {
      builder.append("\t");
    }
    builder.append(label);
    if(value != null) {
      builder.append(": ")
          .append(value);
    }
    System.out.println(builder);
  }

  private class Root {
    public Root() {
    }
  }

  private class Instance {
    private final long id;
    private final long classId;
    private final byte[] fieldBytes;

    public Instance(long id, long classId, byte[] fieldBytes) {
      this.id = id;
      this.classId = classId;
      this.fieldBytes = fieldBytes;
    }
  }

  private class InstanceField {
    private final long stringId;
    private final Type type;

    public InstanceField(long stringId, Type type) {
      this.stringId = stringId;
      this.type = type;
    }
  }

  private class ClassInfo {
    private final long classObjectId;
    private final long superClassObjectID;
    private final int instanceSize;
    private final InstanceField[] instanceFields;

    public ClassInfo(long classObjectId, long superClassObjectID, int instanceSize, InstanceField[] instanceFields) {
      this.classObjectId = classObjectId;
      this.superClassObjectID = superClassObjectID;
      this.instanceSize = instanceSize;
      this.instanceFields = instanceFields;
    }
  }
}
