package com.debugAgent;


import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Invoke as follows:
 * <pre>java -javaagent:target/debug-agent-0.1-SNAPSHOT.jar={opts} application.jar<pre/>
 *
 * Example:
 *  java -javaagent:target/debug-agent-0.1-SNAPSHOT.jar=debug=true;methods=java.net.InetAddress::getByName(java.lang.String)|java.net.InetAddress::getByName(java.lang.String, java.net.InetAddress) -jar application.jar
 *
 *
 * Agent opts (as key1=value1;key2=value2)
 *
 * - debug=true
 *         enable debug logging to standard error
 *
 * - methods=methodDefinitions
 *         definitions of methods to be intercepted separated by "|"
 *
 *         Example: "java.net.InetAddress::getByName(java.lang.String, java.net.InetAddress)|java.net.InetAddress::getByName(java.lang.String)"
 *
 *         Note: If not specified networking methods for DNS lookup and opening of TCP/UDP connections will be intercepted.
 */
public class DebugAgentMain {
    private static final Pattern PATTERN = Pattern.compile("([^:]+)::([^(]+)\\(([^)]+)\\)");
    private static final String TIME_STAMP_STRING_CODE = "java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern(\"yyyy-MM-dd HH:mm:ss.SSS\"))";
    private static final String THREAD_NAME_STRING_CODE = "java.lang.Thread.currentThread().getName()";
    private static final String LOG_PREFIX_STRING_CODE = TIME_STAMP_STRING_CODE + " + \" [\" + " + THREAD_NAME_STRING_CODE  + " + \"] DebugAgent \"";

    private static final String[] DEFAULT_INSTRUMENT_METHODS = new String[] {
            "java.net.InetAddress::getByName(java.lang.String)",
            "java.net.InetAddress::getByName(java.lang.String, java.net.InetAddress)",
            "java.net.Socket::connect(java.net.SocketAddress, int)",
            "java.net.Socket::bind(java.net.SocketAddress)",
            "java.net.ServerSocket::bind(java.net.SocketAddress, int)",
            "java.net.DatagramSocket::bind(java.net.SocketAddress)",
            "java.net.DatagramSocket::connect(java.net.SocketAddress)",
            "java.net.DatagramSocket::connect(java.net.SocketAddress, int)"
    };

    public static void premain(String agentArgs, Instrumentation inst) {
        // Parse args
        Map<String, String> agentArgsMap = parseAgentArgs(agentArgs);

        // Get debug argument
        boolean debug = Objects.equals(agentArgsMap.get("debug"), "true");

        // Get methods argument
        Stream<String> methods = (agentArgsMap.get("methods") == null)
                ? Stream.of(DEFAULT_INSTRUMENT_METHODS)
                : Stream.of(agentArgsMap.get("methods").split("\\|"));

        // Parse method arguments into instruction map
        Map<String,List<MethodDesc>> instructionMap = methods
                .map(String::trim)
                .map(DebugAgentMain::parseMethodDesc)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.groupingBy(MethodDesc::getClassName));

        // Register transformer
        inst.addTransformer(new Transformer(debug, instructionMap));
    }

    private static Map<String, String> parseAgentArgs(String agentArgs) {
        Map<String,String> result = new LinkedHashMap<>();

        if (agentArgs == null) {
            return result;
        }

        for (String arg : agentArgs.split(";")) {
            String[] argParts = arg.split("=");
            if (argParts.length != 2) {
                logError("Ignoring agentArg : " + arg);
                continue;
            }
            result.put(argParts[0].trim(), argParts[1].trim());
        }

        return result;
    }

    private static Optional<MethodDesc> parseMethodDesc(String desc) {
        Matcher matcher = PATTERN.matcher(desc);
        if (matcher.matches()) {
            String className = matcher.group(1);
            String methodName = matcher.group(2);
            String argString = matcher.group(3);
            String[] methodArgs = Stream.of(argString.split(","))
                    .map(String::trim)
                    .toArray(String[]::new);
            return Optional.of(new MethodDesc(className, methodName, methodArgs));
        }
        logError("Invalid method desc " + desc);
        return Optional.empty();
    }

    private static void logError(String msg) {
        System.err.println(">>> DebugAgent ERROR: " + msg);
    }

    private static class Transformer implements ClassFileTransformer {
        private final boolean debug;
        private final Map<String,List<MethodDesc>> instructionMap;

        private Transformer(boolean debug, Map<String, List<MethodDesc>> instrumentMethods) {
            this.debug = debug;
            this.instructionMap = instrumentMethods;
        }

        private void logDebug(String msg) {
            if (debug) {
                System.err.println(">>> DebugAgent DEBUG: " + msg);
            }
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
            className = className.replace("/", ".");

            List<MethodDesc> methods = instructionMap.get(className);
            if (methods != null) {
                return instrumentClass(className, methods);
            }
            return null;
        }

        private byte[] instrumentClass(String className, Collection<MethodDesc> methodDescs) {
            try {
                logDebug("Instrumenting " + className);

                ClassPool pool = ClassPool.getDefault();
                CtClass ctClass = pool.get(className);

                for (MethodDesc methodDesc : methodDescs) {
                    instrumentMethod(pool, ctClass, methodDesc);
                }

                byte[] byteCode = ctClass.toBytecode();
                ctClass.detach();
                return byteCode;
            } catch (Exception ex) {
                logError("Failed to instrument class " + className + " : " + ex.getMessage());
                return null;
            }
        }

        private void instrumentMethod(ClassPool pool, CtClass ctClass, MethodDesc desc) {
            try {
                CtClass[] methodArgs = Stream.of(desc.getMethodArgs())
                        .map(type -> getCtClass(pool, type))
                        .toArray(CtClass[]::new);

                CtMethod ctMethod = ctClass.getDeclaredMethod(desc.getMethodName(), methodArgs);
                logReturn(ctMethod);
            } catch (Exception ex) {
                logError("Failed to instrument class " + ctClass.getName() + ", method " + desc + " : " + ex.getMessage());
            }
        }

        private CtClass getCtClass(ClassPool pool, String className) {
            try {
                if (Objects.equals("int", className)) {
                    return CtClass.intType;
                }
                return pool.getCtClass(className);
            } catch (Exception ex) {
                throw new RuntimeException("Class not found " + className, ex);
            }
        }

        private void logReturn(CtMethod m)  {
            try {
                logDebug("Instrumenting " + m.getDeclaringClass().getSimpleName() + "::" + m.getName());

                // Success handler
                String successCode = getCodeLogMethodArgsAndResult(m, "$_");
                m.insertAfter(successCode);

                // Catch clause / error handler
                String errorCode = "{ " + getCodeLogMethodArgsAndResult(m, "$ex") + "; throw $ex; }";
                CtClass exceptionClass = ClassPool.getDefault().getCtClass("java.lang.Exception");
                m.addCatch(errorCode, exceptionClass, "$ex");
            } catch (Exception ex) {
                logError("Failed to instrument method " + m + " : " + ex);
            }
        }

        private String getCodeLogMethodArgsAndResult(CtMethod m, String result) throws NotFoundException {
            StringBuilder code = new StringBuilder();

            // Prefix
            code.append("System.err.println(")
                    .append(LOG_PREFIX_STRING_CODE)
                    .append(" + ");

            // ClassName::methodName
            code.append("\"")
                    .append(m.getDeclaringClass().getSimpleName())
                    .append("::")
                    .append(m.getName())
                    .append("(\"");

            // Arguments
            code.append(" + ");
            int argCount = m.getParameterTypes().length;
            for (int i = 1; i <= argCount; i++) {
                if (i != 1) {
                    code.append(" + \", \" + ");
                }
                code.append("$").append(i);
            }

            // Right Bracket
            code.append(" + \")\"");

            // Result
            code.append(" + \" => \"");
            code.append(" + ");
            code.append(result);
            code.append(");");

            return code.toString();
        }
    }

    private static class MethodDesc {
        private String className;
        private String methodName;
        private String[] methodArgs;

        private MethodDesc(String className, String methodName, String... methodArgs) {
            this.className = className;
            this.methodName = methodName;
            this.methodArgs = methodArgs;
        }

        public String getClassName() {
            return className;
        }

        public String getMethodName() {
            return methodName;
        }

        public String[] getMethodArgs() {
            return methodArgs;
        }

        @Override
        public String toString() {
            return "MethodDesc{" +
                    "methodName='" + methodName + '\'' +
                    ", methodArgs=" + Arrays.toString(methodArgs) +
                    '}';
        }
    }
}