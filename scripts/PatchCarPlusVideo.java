import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.util.EnumMap;
import java.util.List;

/** Substitutes SDK video calls without changing instruction sizes or control flow. */
class PatchCarPlusVideo {
    private static final String TAP = "Lio/github/shaonialife/carptocar/carplus/CarPlusVideoTap;";
    private static final String SURFACE = "Landroid/view/Surface;";
    private static final String BUFFER = "Ljava/nio/ByteBuffer;";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String LISTENER = "Lcom/ucar/vehiclesdk/IAppSurfaceStateListener;";
    private static final String CAST = "Lcom/ucar/vehiclesdk/cast/CastManager;";
    private static final String QUEUE = "Lcom/ucar/vehiclesdk/player/VideoQueueManager;";

    private enum Call {
        START(
                "Lcom/ucar/vehiclesdk/UCarAdapter;", "lambda$startCast$25$UCarAdapter",
                new ImmutableMethodReference(CAST, "startCast", List.of(SURFACE, "I", "I", "Z", "J", LISTENER), "Z"),
                new ImmutableMethodReference(TAP, "startCast", List.of(OBJECT, SURFACE, "I", "I", "Z", "J", OBJECT), "Z")),
        WFD(
                "Lcom/ucar/vehiclesdk/UCarAdapter$6;", "lambda$onReceivedClientAddress$2$UCarAdapter$6",
                new ImmutableMethodReference(CAST, "startWfdServer", List.of("I", "I", "I", "I", "Z", "Z", "Z"), "V"),
                new ImmutableMethodReference(TAP, "startWfdServer", List.of(OBJECT, "I", "I", "I", "I", "Z", "Z", "Z"), "V")),
        FRAME(
                "Lcom/ucar/vehiclesdk/cast/CastManager$1;", "onVideoDataReceived",
                new ImmutableMethodReference(QUEUE, "putData", List.of(BUFFER, "J"), "V"),
                new ImmutableMethodReference(TAP, "queueFrame", List.of(OBJECT, BUFFER, "J"), "V"));

        final String owner;
        final String caller;
        final ImmutableMethodReference original;
        final ImmutableMethodReference replacement;

        Call(String owner, String caller, ImmutableMethodReference original, ImmutableMethodReference replacement) {
            this.owner = owner;
            this.caller = caller;
            this.original = original;
            this.replacement = replacement;
        }
    }

    public static void main(String[] args) throws Exception {
        var sdk = DexFileFactory.loadDexFile(args[0], null);
        var counts = new EnumMap<Call, Integer>(Call.class);
        for (var type : sdk.getClasses()) {
            for (var method : type.getMethods()) {
                var code = method.getImplementation();
                if (code == null) continue;
                for (var instruction : code.getInstructions()) {
                    var call = target(instruction);
                    if (call == null) continue;
                    if (!type.getType().equals(call.owner) || !method.getName().equals(call.caller)) {
                        throw new IllegalArgumentException("Unexpected ICCOA video caller: " + method);
                    }
                    counts.merge(call, 1, Integer::sum);
                }
            }
        }
        for (var call : Call.values()) {
            if (counts.getOrDefault(call, 0) != 1) {
                throw new IllegalArgumentException("Unexpected ICCOA video call count for " + call);
            }
        }

        var rewriter = new DexRewriter(new RewriterModule() {
            @Override public Rewriter<Instruction> getInstructionRewriter(Rewriters rewriters) {
                return instruction -> {
                    var target = target(instruction);
                    if (target == null) return instruction;
                    if (instruction instanceof Instruction3rc call) {
                        return new ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE,
                                call.getStartRegister(), call.getRegisterCount(), target.replacement);
                    }
                    var call = (Instruction35c) instruction;
                    return new ImmutableInstruction35c(Opcode.INVOKE_STATIC, call.getRegisterCount(),
                            call.getRegisterC(), call.getRegisterD(), call.getRegisterE(),
                            call.getRegisterF(), call.getRegisterG(), target.replacement);
                };
            }
        });
        DexPool.writeTo(args[1], rewriter.getDexFileRewriter().rewrite(sdk));
    }

    private static Call target(Instruction instruction) {
        if (instruction.getOpcode() == Opcode.INVOKE_VIRTUAL_RANGE && instruction instanceof Instruction3rc call) {
            if (Call.START.original.equals(call.getReference())) return Call.START;
            if (Call.WFD.original.equals(call.getReference())) return Call.WFD;
        }
        if (instruction.getOpcode() == Opcode.INVOKE_VIRTUAL && instruction instanceof Instruction35c call
                && Call.FRAME.original.equals(call.getReference())) return Call.FRAME;
        return null;
    }
}
