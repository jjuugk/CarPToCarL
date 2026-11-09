import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/** Instruments the bundled SDK's PCM writes and local audio focus calls. */
class PatchCarPlusAudio {
    private static final String TRACK = "Landroid/media/AudioTrack;";
    private static final String MANAGER = "Landroid/media/AudioManager;";
    private static final String FOCUS_REQUEST = "Landroid/media/AudioFocusRequest;";
    private static final String PLAYER = "Lcom/ucar/vehiclesdk/player/AudioPlayerV2$PlayThread;";
    private static final String PLAYER_MANAGER = "Lcom/ucar/vehiclesdk/player/AudioPlayerMgr;";

    private enum Call {
        PCM(TRACK, "write", List.of("[B", "I", "I", "I"), PLAYER, "run", 2),
        FOCUS(MANAGER, "requestAudioFocus", List.of(FOCUS_REQUEST), PLAYER_MANAGER, "requestAudioFocus", 1),
        ABANDON(MANAGER, "abandonAudioFocusRequest", List.of(FOCUS_REQUEST), PLAYER_MANAGER, "abandonAudioFocus", 1);

        final MethodReference original;
        final MethodReference tap;
        final String owner;
        final String method;
        final int expected;

        Call(String receiver, String name, List<String> args, String owner, String method, int expected) {
            this.original = new ImmutableMethodReference(receiver, name, args, "I");
            var staticArgs = new ArrayList<String>();
            staticArgs.add(receiver);
            staticArgs.addAll(args);
            this.tap = new ImmutableMethodReference(
                    "Lio/github/shaonialife/carptocar/carplus/CarPlusAudioTap;", name, staticArgs, "I");
            this.owner = owner;
            this.method = method;
            this.expected = expected;
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
                    if (!type.getType().equals(call.owner) || !method.getName().equals(call.method)) {
                        throw new IllegalArgumentException("Unexpected SDK audio call: " + method);
                    }
                    counts.merge(call, 1, Integer::sum);
                }
            }
        }
        for (var call : Call.values()) {
            if (counts.getOrDefault(call, 0) != call.expected) {
                throw new IllegalArgumentException("Unexpected SDK audio call count for " + call);
            }
        }

        var rewriter = new DexRewriter(new RewriterModule() {
            @Override public Rewriter<Instruction> getInstructionRewriter(Rewriters rewriters) {
                return instruction -> {
                    var target = target(instruction);
                    if (target == null) return instruction;
                    var call = (Instruction35c) instruction;
                    return new ImmutableInstruction35c(Opcode.INVOKE_STATIC, call.getRegisterCount(),
                            call.getRegisterC(), call.getRegisterD(), call.getRegisterE(),
                            call.getRegisterF(), call.getRegisterG(), target.tap);
                };
            }
        });
        DexPool.writeTo(args[1], rewriter.getDexFileRewriter().rewrite(sdk));
    }

    private static Call target(Instruction instruction) {
        if (instruction.getOpcode() != Opcode.INVOKE_VIRTUAL || !(instruction instanceof Instruction35c call)) {
            return null;
        }
        for (var candidate : Call.values()) {
            if (candidate.original.equals(call.getReference())) return candidate;
        }
        return null;
    }
}
