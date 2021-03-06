package com.alibaba.dcm.agent;

import com.alibaba.dcm.DnsCacheManipulator;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Jerry Lee (oldratlee at gmail dot com)
 * @since 1.4.0
 */
public class DcmAgent {
    public static final String FILE = "file";
    public static final String DCM_AGENT_SUCCESS_MARK_LINE = "!!DCM SUCCESS!!";

    public static void agentmain(String agentArgument) throws Exception {
        System.out.printf("%s: attached with agent argument: %s.\n", DcmAgent.class.getName(), agentArgument);

        agentArgument = agentArgument.trim();
        if (agentArgument.isEmpty()) {
            System.out.println(DcmAgent.class.getName() + ": agent argument is blank, do nothing!");
            return;
        }

        initAction2Method();

        FileOutputStream fileOutputStream = null;
        try {
            final Map<String, List<String>> action2Arguments = parseArgument(agentArgument);

            PrintWriter filePrinter = null;
            if (action2Arguments.containsKey(FILE)) {
                fileOutputStream = new FileOutputStream(action2Arguments.get(FILE).get(0), false);
                final OutputStreamWriter outputStreamWriter = new OutputStreamWriter(fileOutputStream, "UTF-8");

                filePrinter = new PrintWriter(outputStreamWriter, true);
                action2Arguments.remove(FILE);
            }

            if (action2Arguments.isEmpty()) {
                System.out.println(DcmAgent.class.getName() + ": No action in agent argument, do nothing!");
                if (filePrinter != null) {
                    filePrinter.printf("No action in agent argument, do nothing! agent argument: %s.\n", agentArgument);
                }
                return;
            }

            boolean allSuccess = true;

            for (Map.Entry<String, List<String>> entry : action2Arguments.entrySet()) {
                final String action = entry.getKey();
                final List<String> arguments = entry.getValue();

                StringBuilder argumentString = new StringBuilder();
                for (String argument : arguments) {
                    if (argumentString.length() > 0) {
                        argumentString.append(" ");
                    }
                    argumentString.append(argument);
                }

                if (!action2Method.containsKey(action)) {
                    System.out.printf("%s: Unknown action %s, ignore! action: %<s %s!\n", DcmAgent.class.getName(), action, argumentString);
                    if (filePrinter != null) {
                        filePrinter.printf("Unknown action %s, ignore! action: %<s %s !\n", action, argumentString);
                    }
                    continue;
                }

                try {
                    final Object result = doAction(action, arguments.toArray(new String[0]));
                    printResult(action, result, filePrinter);
                } catch (Exception e) {
                    allSuccess = false;

                    final StringWriter w = new StringWriter();
                    e.printStackTrace(new PrintWriter(w));
                    final String exString = w.toString();

                    System.out.printf("%s: Error to do action %s %s, cause: %s\n", DcmAgent.class.getName(), action, argumentString, exString);
                    if (filePrinter != null) {
                        filePrinter.printf("Error to do action %s %s, cause: %s\n", action, argumentString, exString);
                    }
                }
            }

            if (allSuccess && filePrinter != null) {
                filePrinter.println(DCM_AGENT_SUCCESS_MARK_LINE);
            }
        } finally {
            if (fileOutputStream != null) {
                try {
                    fileOutputStream.close();
                } catch (Throwable e) {
                    // do nothing!
                }
            }
        }
    }

    static Map<String, List<String>> parseArgument(String argument) {
        final String[] split = argument.split("\\s+");

        int idx = 0;
        Map<String, List<String>> action2Arguments = new HashMap<String, List<String>>();
        while (idx < split.length) {
            final String action = split[idx++];
            if (!action2Method.containsKey(action)) {
                continue; // TODO error message
            }

            List<String> arguments = new ArrayList<String>();
            while (idx < split.length) {
                if (action2Method.containsKey(split[idx])) {
                    break;
                }
                arguments.add(split[idx++]);
            }
            action2Arguments.put(action, arguments);
        }

        return action2Arguments;
    }

    static Object doAction(String action, String[] arguments) throws Exception {
        Method method = action2Method.get(action);

        final Class<?>[] parameterTypes = method.getParameterTypes();
        final Object[] methodArgs = convertStringArray2Arguments(action, arguments, parameterTypes);
        return method.invoke(null, methodArgs);
    }

    static void printResult(String action, Object result, PrintWriter writer) {
        final Method method = action2Method.get(action);
        if (method.getReturnType() != void.class) {
            if (writer != null) {
                writer.println(result.toString());
            }
        }
        if (writer != null) {
            writer.printf("%s DONE.\n", action);
        }
    }

    static volatile Map<String, Method> action2Method;

    static synchronized void initAction2Method() throws Exception {
        if (action2Method != null) return;

        Map<String, Method> map = new HashMap<String, Method>();
        map.put("set", DnsCacheManipulator.class.getMethod("setDnsCache", String.class, String[].class));
        map.put("get", DnsCacheManipulator.class.getMethod("getDnsCache", String.class));
        map.put("rm", DnsCacheManipulator.class.getMethod("removeDnsCache", String.class));

        map.put("list", DnsCacheManipulator.class.getMethod("getWholeDnsCache"));
        map.put("clear", DnsCacheManipulator.class.getMethod("clearDnsCache"));

        map.put("setPolicy", DnsCacheManipulator.class.getMethod("setDnsCachePolicy", int.class));
        map.put("getPolicy", DnsCacheManipulator.class.getMethod("getDnsCachePolicy"));
        map.put("setNegativePolicy", DnsCacheManipulator.class.getMethod("setDnsNegativeCachePolicy", int.class));
        map.put("getNegativePolicy", DnsCacheManipulator.class.getMethod("getDnsNegativeCachePolicy"));

        map.put(FILE, null); // FAKE KEY

        action2Method = map;
    }

    static Object[] convertStringArray2Arguments(String action, String[] arguments, Class<?>[] parameterTypes) {
        if (arguments.length < parameterTypes.length) {
            final String message = String.format("action %s need more argument! arguments: %s", action, Arrays.toString(arguments));
            throw new IllegalStateException(message);
        }
        if (parameterTypes.length == 0) {
            return new Object[0];
        }

        final Object[] methodArgs = new Object[parameterTypes.length];

        final int lastArgumentIdx = parameterTypes.length - 1;
        if (parameterTypes[(lastArgumentIdx)] == String[].class) {
            // set all tail method argument of type String[] 
            String[] varArgs = new String[arguments.length - lastArgumentIdx];
            System.arraycopy(arguments, lastArgumentIdx, varArgs, 0, varArgs.length);
            methodArgs[(lastArgumentIdx)] = varArgs;
        } else if (arguments.length > parameterTypes.length) {
            String message = String.format("Too more arguments for Action %s! arguments: %s", action, Arrays.toString(arguments));
            throw new IllegalStateException(message);
        }

        for (int i = 0; i < parameterTypes.length; i++) {
            if (methodArgs[i] != null) { // already set
                continue;
            }

            Class<?> parameterType = parameterTypes[i];
            final String argument = arguments[i];
            if (parameterType.equals(String.class)) {
                methodArgs[i] = argument;
            } else if (parameterType.equals(int.class)) {
                methodArgs[i] = Integer.parseInt(argument);
            } else {
                final String message = String.format("Unexpected method type %s! Bug!!", parameterType.getName());
                throw new IllegalStateException(message);
            }
        }

        return methodArgs;
    }
}
