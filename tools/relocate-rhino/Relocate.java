import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

/**
 * Relocalise org.mozilla.javascript -> com.frunified.rhino (et org.mozilla.classfile
 * -> com.frunified.rhino.classfile et tout org/mozilla/*) dans un jar de classes :
 * bytecode reecrit par ASM, string constants LDC/ConstantValue, chemins de
 * ressources. Evite que le Rhino stock embarque par l'app CloudStream (present
 * dans son APK, classes8.dex) masque notre Rhino patche via le classloader
 * parent-first.
 */
public class Relocate {

    static String mapFile(String name) {
        if (name.startsWith("org/mozilla/javascript")) {
            return "com/frunified/rhino" + name.substring("org/mozilla/javascript".length());
        }
        if (name.startsWith("org/mozilla/")) {
            return "com/frunified/rhino" + name.substring("org/mozilla".length());
        }
        return name;
    }

    static String mapDot(String name) {
        if (name.startsWith("org.mozilla.javascript")) {
            return "com.frunified.rhino" + name.substring("org.mozilla.javascript".length());
        }
        if (name.startsWith("org.mozilla.")) {
            return "com.frunified.rhino" + name.substring("org.mozilla".length());
        }
        return name;
    }

    /** Remplace dans les strings aussi les formes à slashs (descripteurs). */
    static String mapAny(String s) {
        String r = s.replace("org/mozilla/javascript", "com/frunified/rhino");
        r = r.replace("org/mozilla/classfile", "com/frunified/rhino/classfile");
        return mapDot(r);
    }

    public static void main(String[] args) throws Exception {
        String inJar = args[0], outJar = args[1];
        Remapper remapper = new Remapper() {
            @Override public String map(String internalName) {
                return mapFile(internalName);
            }
            @Override public Object mapValue(Object value) {
                if (value instanceof String) return mapDot((String) value);
                return super.mapValue(value);
            }
        };
        try (JarInputStream jin = new JarInputStream(new FileInputStream(inJar));
             JarOutputStream jout = new JarOutputStream(new FileOutputStream(outJar))) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            JarEntry e;
            while ((e = jin.getNextJarEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory()) continue;
                if (!seen.add(name)) continue;
                if (name.equals("module-info.class") || name.endsWith("/module-info.class")
                        || name.endsWith("/package-info.class")) continue;
                byte[] data = jin.readAllBytes();
                if (name.endsWith(".class")) {
                    ClassReader cr = new ClassReader(data);
                    ClassWriter cw = new ClassWriter(0);
                    ClassVisitor rem = new ClassRemapper(cw, remapper);
                    ClassVisitor strings = new ClassVisitor(Opcodes.ASM9, rem) {
                        @Override public MethodVisitor visitMethod(
                                int access, String n, String d, String sig, String[] exc) {
                            MethodVisitor mv = super.visitMethod(access, n, d, sig, exc);
                            return new MethodVisitor(Opcodes.ASM9, mv) {
                                @Override public void visitLdcInsn(Object value) {
                                    if (value instanceof String) {
                                        super.visitLdcInsn(mapAny((String) value));
                                    } else {
                                        super.visitLdcInsn(value);
                                    }
                                }
                            };
                        }
                        @Override public FieldVisitor visitField(int access, String n, String d, String s, Object v) {
                            if (v instanceof String) v = mapAny((String) v);
                            return super.visitField(access, n, d, s, v);
                        }
                    };
                    cr.accept(strings, 0);
                    data = cw.toByteArray();
                    name = mapFile(name);
                } else {
                    byte[] old = data;
                    if (name.startsWith("META-INF/services/")) {
                        String txt = new String(old, "UTF-8");
                        txt = mapAny(txt);
                        data = txt.getBytes("UTF-8");
                        name = "META-INF/services/" + mapDot(name.substring("META-INF/services/".length()));
                    } else {
                        name = mapFile(name);
                    }
                }
                JarEntry ne = new JarEntry(name);
                ne.setTime(0);
                jout.putNextEntry(ne);
                jout.write(data);
                jout.closeEntry();
            }
        }
    }
}
