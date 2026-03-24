// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.udf;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.objectweb.asm.Opcodes.AALOAD;
import static org.objectweb.asm.Opcodes.AASTORE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ANEWARRAY;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.F_APPEND;
import static org.objectweb.asm.Opcodes.F_CHOP;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.IF_ICMPGE;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INTEGER;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.ISTORE;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_8;

public class CallStubGenerator {
    // generate call stub name
    public static final String CLAZZ_NAME = "com/starrocks/udf/gen/CallStub";
    public static final String GEN_KEYWORD = "com.starrocks.udf.gen";

    // A ClassWriter that uses COMPUTE_FRAMES so frame maps are calculated automatically.
    // getCommonSuperClass is overridden to avoid class-loading issues at stub generation time.
    private static ClassWriter newAutoFrameWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                // Conservative: always return Object.  This is correct for the simple
                // control-flow patterns in our generated stubs.
                return "java/lang/Object";
            }
        };
    }

    // Helper: emit an integer push that handles all int ranges.
    private static void visitIntConst(MethodVisitor mv, int value) {
        if (value >= -1 && value <= 5) {
            mv.visitInsn(ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(org.objectweb.asm.Opcodes.BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            mv.visitIntInsn(org.objectweb.asm.Opcodes.SIPUSH, value);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    // -----------------------------------------------------------------------
    // UDAF batch-update stub generator
    //
    // Non-varargs (e.g. update(State, Integer, String)):
    //   public static void batchCallV(int rows, UDAF obj, State var0, Integer[] col0, String[] col1)
    //   { for (int i=0;i<rows;i++) obj.update(var0, col0[i], col1[i]); }
    //
    // Varargs (e.g. update(State, Integer...)) with numActualVarArgs=3:
    //   public static void batchCallV(int rows, UDAF obj, State var0,
    //                                 Integer[] col0, Integer[] col1, Integer[] col2)
    //   { for (int i=0;i<rows;i++) { Integer[] a={col0[i],col1[i],col2[i]}; obj.update(var0,a); } }
    // -----------------------------------------------------------------------
    private static class AggBatchCallGenerator {
        AggBatchCallGenerator(Class<?> clazz, Method update, int numActualVarArgs) {
            this.udafClazz = clazz;
            this.udafUpdate = update;
            this.numActualVarArgs = numActualVarArgs;
            // Use auto-frame writer for varargs (avoids manual StackMapTable management);
            // use plain writer for non-varargs to preserve the original behaviour.
            this.writer = update.isVarArgs() ? newAutoFrameWriter() : new ClassWriter(0);
        }

        private final Class<?> udafClazz;
        private final Method udafUpdate;
        private final int numActualVarArgs;
        private final ClassWriter writer;

        void declareCallStubClazz() {
            writer.visit(V1_8, ACC_PUBLIC, CLAZZ_NAME, null, "java/lang/Object", null);
        }

        void genBatchUpdateSingle() {
            if (udafUpdate.isVarArgs()) {
                genVarargs();
            } else {
                genNonVarargs();
            }
        }

        // Original non-varargs implementation (unchanged).
        private void genNonVarargs() {
            final Parameter[] parameters = udafUpdate.getParameters();
            StringBuilder desc = new StringBuilder("(");
            desc.append("I");
            desc.append(Type.getDescriptor(udafClazz));
            for (int i = 0; i < parameters.length; i++) {
                final Class<?> type = parameters[i].getType();
                if (type.isPrimitive()) {
                    throw new UnsupportedOperationException("Unsupported Primitive Type:" + type.getTypeName());
                }
                if (i > 0) {
                    desc.append("[");
                }
                desc.append(Type.getDescriptor(type));
            }

            final Class<?> returnType = udafUpdate.getReturnType();
            if (returnType != void.class) {
                throw new UnsupportedOperationException("Unsupported return Type:" + returnType.getTypeName());
            }
            desc.append(")V");

            final MethodVisitor batchCall =
                    writer.visitMethod(ACC_PUBLIC + ACC_STATIC, "batchCallV", desc.toString(), null,
                            new String[] {"java/lang/Exception"});
            batchCall.visitCode();

            final Label l0 = new Label();
            batchCall.visitLabel(l0);
            batchCall.visitInsn(ICONST_0);
            int iIdx = 2 + parameters.length;
            batchCall.visitVarInsn(ISTORE, iIdx);

            final Label l1 = new Label();
            batchCall.visitLabel(l1);
            batchCall.visitFrame(F_APPEND, 1, new Object[] {INTEGER}, 0, null);

            batchCall.visitVarInsn(ILOAD, iIdx);
            batchCall.visitVarInsn(ILOAD, 0);

            final Label l2 = new Label();
            batchCall.visitJumpInsn(IF_ICMPGE, l2);

            final Label l3 = new Label();
            batchCall.visitLabel(l3);
            batchCall.visitVarInsn(ALOAD, 1);
            batchCall.visitVarInsn(ALOAD, 2);

            for (int i = 3; i < 3 + parameters.length - 1; i++) {
                batchCall.visitVarInsn(ALOAD, i);
                batchCall.visitVarInsn(ILOAD, iIdx);
                batchCall.visitInsn(AALOAD);
            }

            batchCall.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(udafClazz), udafUpdate.getName(),
                    Type.getMethodDescriptor(udafUpdate), false);
            final Label l4 = new Label();
            batchCall.visitLabel(l4);
            batchCall.visitIincInsn(iIdx, 1);
            batchCall.visitJumpInsn(GOTO, l1);

            batchCall.visitLabel(l2);
            batchCall.visitFrame(F_CHOP, 1, new Object[] {INTEGER}, 0, null);
            batchCall.visitInsn(RETURN);

            final Label l5 = new Label();
            batchCall.visitLabel(l5);
            int callStubInputParameters = 3 + parameters.length;
            batchCall.visitMaxs(callStubInputParameters, callStubInputParameters + 1);
            batchCall.visitEnd();
        }

        // Varargs implementation.
        // Slot layout: 0=rows, 1=obj, 2=state, 3..3+N-1=col0..colN-1, 3+N=i, 3+N+1=args
        private void genVarargs() {
            final Parameter[] parameters = udafUpdate.getParameters();
            // parameters[0] = State, parameters[1] = T[] (varargs component array type)
            Class<?> stateType = parameters[0].getType();
            Class<?> varargArrayType = parameters[1].getType(); // e.g. Integer[]
            Class<?> componentType = varargArrayType.getComponentType(); // e.g. Integer

            // Build descriptor: (I, UDAF, State, T[]col0, ..., T[]colN-1) V
            StringBuilder desc = new StringBuilder("(");
            desc.append("I");
            desc.append(Type.getDescriptor(udafClazz));
            desc.append(Type.getDescriptor(stateType));
            String colDesc = "[" + Type.getDescriptor(componentType);
            for (int i = 0; i < numActualVarArgs; i++) {
                desc.append(colDesc);
            }
            desc.append(")V");

            final MethodVisitor mv =
                    writer.visitMethod(ACC_PUBLIC + ACC_STATIC, "batchCallV", desc.toString(), null,
                            new String[] {"java/lang/Exception"});
            mv.visitCode();

            int iIdx   = 3 + numActualVarArgs;
            int argsIdx = iIdx + 1;

            // i = 0
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, iIdx);

            final Label loopStart = new Label();
            final Label loopEnd   = new Label();

            mv.visitLabel(loopStart);
            mv.visitVarInsn(ILOAD, iIdx);
            mv.visitVarInsn(ILOAD, 0);
            mv.visitJumpInsn(IF_ICMPGE, loopEnd);

            // T[] args = new T[N]
            visitIntConst(mv, numActualVarArgs);
            mv.visitTypeInsn(ANEWARRAY, Type.getInternalName(componentType));
            mv.visitVarInsn(ASTORE, argsIdx);

            // args[j] = colJ[i]
            for (int j = 0; j < numActualVarArgs; j++) {
                mv.visitVarInsn(ALOAD, argsIdx);
                visitIntConst(mv, j);
                mv.visitVarInsn(ALOAD, 3 + j);
                mv.visitVarInsn(ILOAD, iIdx);
                mv.visitInsn(AALOAD);
                mv.visitInsn(AASTORE);
            }

            // obj.update(state, args)
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, argsIdx);
            mv.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(udafClazz), udafUpdate.getName(),
                    Type.getMethodDescriptor(udafUpdate), false);

            mv.visitIincInsn(iIdx, 1);
            mv.visitJumpInsn(GOTO, loopStart);

            mv.visitLabel(loopEnd);
            mv.visitInsn(RETURN);

            // With COMPUTE_FRAMES, visitMaxs values are computed automatically.
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        void finish() {
            writer.visitEnd();
        }

        byte[] getByteCode() {
            return writer.toByteArray();
        }
    }

    /**
     * Generate a batch-update stub for a UDAF update method.
     *
     * @param clazz           UDAF class
     * @param method          the update method
     * @param numActualVarArgs actual number of varargs input columns (ignored when method is not varargs)
     */
    public static byte[] generateCallStubV(Class<?> clazz, Method method, int numActualVarArgs) {
        final AggBatchCallGenerator gen = new AggBatchCallGenerator(clazz, method, numActualVarArgs);
        gen.declareCallStubClazz();
        gen.genBatchUpdateSingle();
        gen.finish();
        return gen.getByteCode();
    }

    /** Overload for non-varargs UDAF update methods. */
    public static byte[] generateCallStubV(Class<?> clazz, Method method) {
        return generateCallStubV(clazz, method, 0);
    }

    // -----------------------------------------------------------------------
    // Scalar UDF batch-evaluate stub generator
    //
    // Non-varargs (e.g. evaluate(String, Integer)):
    //   public static RetType[] batchCallV(int rows, UDF obj, String[] col0, Integer[] col1)
    //   { RetType[] r=new RetType[rows]; for(int i=0;i<rows;i++) r[i]=obj.evaluate(col0[i],col1[i]); return r; }
    //
    // Varargs (e.g. evaluate(String...)) with numActualVarArgs=3:
    //   public static String[] batchCallV(int rows, UDF obj, String[] col0, String[] col1, String[] col2)
    //   { String[] r=new String[rows]; for(int i=0;i<rows;i++){String[] a={col0[i],col1[i],col2[i]};r[i]=obj.evaluate(a);}return r;}
    // -----------------------------------------------------------------------
    private static class BatchCallEvaluateGenerator {
        BatchCallEvaluateGenerator(Class<?> clazz, Method evaluate, int numActualVarArgs) {
            this.udfClazz = clazz;
            this.udfEvaluate = evaluate;
            this.numActualVarArgs = numActualVarArgs;
            // Use auto-frame writer for varargs (avoids manual StackMapTable management);
            // use plain writer for non-varargs to preserve the original behaviour.
            this.writer = evaluate.isVarArgs() ? newAutoFrameWriter() : new ClassWriter(0);
        }

        private final Class<?> udfClazz;
        private final Method udfEvaluate;
        private final int numActualVarArgs;
        private final ClassWriter writer;

        void declareCallStubClazz() {
            writer.visit(V1_8, ACC_PUBLIC, CLAZZ_NAME, null, "java/lang/Object", null);
        }

        void genBatchUpdateSingle() {
            if (udfEvaluate.isVarArgs()) {
                genVarargs();
            } else {
                genNonVarargs();
            }
        }

        // Original non-varargs implementation (unchanged).
        private void genNonVarargs() {
            final Parameter[] parameters = udfEvaluate.getParameters();
            StringBuilder desc = new StringBuilder("(");
            desc.append("I");
            desc.append(Type.getDescriptor(udfClazz));
            for (Parameter parameter : parameters) {
                final Class<?> type = parameter.getType();
                if (type.isPrimitive()) {
                    throw new UnsupportedOperationException("Unsupported Primitive Type:" + type.getTypeName());
                }
                desc.append("[");
                desc.append(Type.getDescriptor(type));
            }

            final Class<?> returnType = udfEvaluate.getReturnType();
            if (returnType.isPrimitive()) {
                throw new UnsupportedOperationException("Unsupported return Type:" + returnType.getTypeName());
            }
            desc.append(")");
            desc.append("[").append(Type.getDescriptor(returnType));

            final MethodVisitor batchCall =
                    writer.visitMethod(ACC_PUBLIC + ACC_STATIC, "batchCallV", desc.toString(), null,
                            new String[] {"java/lang/Exception"});
            batchCall.visitCode();

            int resIndex = 2 + parameters.length;
            int iIndex   = resIndex + 1;

            final Label l0 = new Label();
            batchCall.visitLabel(l0);
            batchCall.visitVarInsn(ILOAD, 0);
            batchCall.visitTypeInsn(ANEWARRAY, Type.getInternalName(returnType));
            batchCall.visitVarInsn(ASTORE, resIndex);

            final Label l1 = new Label();
            batchCall.visitLabel(l1);
            batchCall.visitInsn(ICONST_0);
            batchCall.visitVarInsn(ISTORE, iIndex);

            final Label l2 = new Label();
            batchCall.visitLabel(l2);
            batchCall.visitFrame(F_APPEND, 2, new Object[] {"[" + Type.getDescriptor(returnType), INTEGER}, 0, null);
            batchCall.visitVarInsn(ILOAD, iIndex);
            batchCall.visitVarInsn(ILOAD, 0);

            final Label l3 = new Label();
            batchCall.visitJumpInsn(IF_ICMPGE, l3);

            final Label l4 = new Label();
            batchCall.visitLabel(l4);
            batchCall.visitVarInsn(ALOAD, resIndex);
            batchCall.visitVarInsn(ILOAD, iIndex);
            batchCall.visitVarInsn(ALOAD, 1);
            int padding = 2;
            for (int i = 0; i < parameters.length; i++) {
                batchCall.visitVarInsn(ALOAD, i + padding);
                batchCall.visitVarInsn(ILOAD, iIndex);
                batchCall.visitInsn(AALOAD);
            }

            batchCall.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(udfClazz), udfEvaluate.getName(),
                    Type.getMethodDescriptor(udfEvaluate), false);
            batchCall.visitInsn(AASTORE);

            final Label l5 = new Label();
            batchCall.visitLabel(l5);
            batchCall.visitIincInsn(iIndex, 1);
            batchCall.visitJumpInsn(GOTO, l2);

            batchCall.visitLabel(l3);
            batchCall.visitFrame(F_CHOP, 1, new Object[] {INTEGER}, 0, null);
            batchCall.visitVarInsn(ALOAD, resIndex);
            batchCall.visitInsn(ARETURN);

            final Label l6 = new Label();
            batchCall.visitLabel(l6);
            batchCall.visitLocalVariable("i", "I", null, l2, l3, iIndex);
            batchCall.visitLocalVariable("res", Type.getDescriptor(returnType), null, l1, l6, resIndex);
            batchCall.visitMaxs(iIndex + 1, iIndex + 1);
            batchCall.visitEnd();
        }

        // Varargs implementation.
        // Slot layout: 0=rows, 1=obj, 2..2+N-1=col0..colN-1, 2+N=res, 2+N+1=i, 2+N+2=args
        private void genVarargs() {
            final Parameter[] parameters = udfEvaluate.getParameters();
            // For varargs evaluate(T... args): parameters[0].getType() = T[]
            Class<?> varargArrayType = parameters[0].getType(); // e.g. String[]
            Class<?> componentType   = varargArrayType.getComponentType(); // e.g. String

            final Class<?> returnType = udfEvaluate.getReturnType();
            if (returnType.isPrimitive()) {
                throw new UnsupportedOperationException("Unsupported return Type:" + returnType.getTypeName());
            }

            // Descriptor: (I, UDF, T[]col0, ..., T[]colN-1) T[]
            StringBuilder desc = new StringBuilder("(");
            desc.append("I");
            desc.append(Type.getDescriptor(udfClazz));
            String colDesc = "[" + Type.getDescriptor(componentType);
            for (int i = 0; i < numActualVarArgs; i++) {
                desc.append(colDesc);
            }
            desc.append(")");
            desc.append("[").append(Type.getDescriptor(returnType));

            final MethodVisitor mv =
                    writer.visitMethod(ACC_PUBLIC + ACC_STATIC, "batchCallV", desc.toString(), null,
                            new String[] {"java/lang/Exception"});
            mv.visitCode();

            int resIdx  = 2 + numActualVarArgs;
            int iIdx    = resIdx + 1;
            int argsIdx = iIdx + 1;

            // RetType[] res = new RetType[rows]
            mv.visitVarInsn(ILOAD, 0);
            mv.visitTypeInsn(ANEWARRAY, Type.getInternalName(returnType));
            mv.visitVarInsn(ASTORE, resIdx);

            // i = 0
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, iIdx);

            final Label loopStart = new Label();
            final Label loopEnd   = new Label();

            mv.visitLabel(loopStart);
            mv.visitVarInsn(ILOAD, iIdx);
            mv.visitVarInsn(ILOAD, 0);
            mv.visitJumpInsn(IF_ICMPGE, loopEnd);

            // T[] args = new T[N]
            visitIntConst(mv, numActualVarArgs);
            mv.visitTypeInsn(ANEWARRAY, Type.getInternalName(componentType));
            mv.visitVarInsn(ASTORE, argsIdx);

            // args[j] = colJ[i]
            for (int j = 0; j < numActualVarArgs; j++) {
                mv.visitVarInsn(ALOAD, argsIdx);
                visitIntConst(mv, j);
                mv.visitVarInsn(ALOAD, 2 + j);
                mv.visitVarInsn(ILOAD, iIdx);
                mv.visitInsn(AALOAD);
                mv.visitInsn(AASTORE);
            }

            // res[i] = obj.evaluate(args)
            mv.visitVarInsn(ALOAD, resIdx);
            mv.visitVarInsn(ILOAD, iIdx);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, argsIdx);
            mv.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(udfClazz), udfEvaluate.getName(),
                    Type.getMethodDescriptor(udfEvaluate), false);
            mv.visitInsn(AASTORE);

            mv.visitIincInsn(iIdx, 1);
            mv.visitJumpInsn(GOTO, loopStart);

            mv.visitLabel(loopEnd);
            mv.visitVarInsn(ALOAD, resIdx);
            mv.visitInsn(ARETURN);

            // With COMPUTE_FRAMES, visitMaxs values are computed automatically.
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        void finish() {
            writer.visitEnd();
        }

        byte[] getByteCode() {
            return writer.toByteArray();
        }
    }

    /**
     * Generate a batch-evaluate stub for a scalar UDF evaluate method.
     *
     * @param clazz           UDF class
     * @param method          the evaluate method
     * @param numActualVarArgs actual number of varargs input columns (ignored when method is not varargs)
     */
    public static byte[] generateScalarCallStub(Class<?> clazz, Method method, int numActualVarArgs) {
        final BatchCallEvaluateGenerator gen = new BatchCallEvaluateGenerator(clazz, method, numActualVarArgs);
        gen.declareCallStubClazz();
        gen.genBatchUpdateSingle();
        gen.finish();
        return gen.getByteCode();
    }

    /** Overload for non-varargs scalar evaluate methods. */
    public static byte[] generateScalarCallStub(Class<?> clazz, Method method) {
        return generateScalarCallStub(clazz, method, 0);
    }
}
