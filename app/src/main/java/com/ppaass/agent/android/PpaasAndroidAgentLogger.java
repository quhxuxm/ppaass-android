package com.ppaass.agent.android;

import android.util.Log;
import com.ppaass.common.log.IPpaassLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class PpaasAndroidAgentLogger implements IPpaassLogger {
    private static class LoggerParamWrapper {
        private Object[] noneExceptionParams;
        private Object[] exceptionParams;
    }

    public PpaasAndroidAgentLogger() {
    }

    private String findCurrentLoggerClassStackTraceIndex(StackTraceElement[] stackTraceElements) {
        for (int i = 0; i < stackTraceElements.length; i++) {
            if (stackTraceElements[i].getClassName().equals(PpaasAndroidAgentLogger.class.getName())) {
                return stackTraceElements[i + 1].getClassName();
            }
        }
        return PpaasAndroidAgentLogger.class.getName();
    }

    private LoggerParamWrapper filterNoneExceptionParams(Object[] params) {
        List<Object> noneExceptionParams = new ArrayList<>();
        List<Object> exceptionParams = new ArrayList<>();
        for (Object param : params) {
            if (param instanceof Throwable) {
                exceptionParams.add(param);
            } else {
                noneExceptionParams.add(param);
            }
        }
        LoggerParamWrapper result = new LoggerParamWrapper();
        result.exceptionParams = exceptionParams.toArray();
        result.noneExceptionParams = noneExceptionParams.toArray();
        return result;
    }

    private <T> void concretePrintLogWithTargetClass(int level, Class<T> targetClass, Supplier<String> logSupplier,
                                                     Supplier<Object[]> argumentsSupplier) {
        Object[] arguments = new Object[]{};
        if (argumentsSupplier != null) {
            arguments = argumentsSupplier.get();
        }
        LoggerParamWrapper loggerParamWrapper = this.filterNoneExceptionParams(arguments);
        String logMessageFormat = logSupplier.get();
        Object exception = null;
        if (loggerParamWrapper.exceptionParams.length > 0) {
            exception = loggerParamWrapper.exceptionParams[0];
        }
        if (exception != null) {
            switch (level) {
                case Log.INFO:
                    Log.i(targetClass.getName(),
                            String.format(logMessageFormat, loggerParamWrapper.noneExceptionParams),
                            (Throwable) exception);
                    break;
                case Log.ERROR:
                    Log.e(targetClass.getName(),
                            String.format(logMessageFormat, loggerParamWrapper.noneExceptionParams),
                            (Throwable) exception);
                    break;
                case Log.WARN:
                    Log.w(targetClass.getName(),
                            String.format(logMessageFormat, loggerParamWrapper.noneExceptionParams),
                            (Throwable) exception);
                    break;
                case Log.DEBUG:
                    Log.d(targetClass.getName(),
                            String.format(logMessageFormat, loggerParamWrapper.noneExceptionParams),
                            (Throwable) exception);
                    break;
                case Log.VERBOSE:
                    Log.v(targetClass.getName(),
                            String.format(logMessageFormat, loggerParamWrapper.noneExceptionParams),
                            (Throwable) exception);
                    break;
            }
        } else {
            switch (level) {
                case Log.INFO:
                    Log.i(targetClass.getName(),
                            String.format(logMessageFormat, loggerParamWrapper.noneExceptionParams));
                    break;
                case Log.ERROR:
                    Log.e(targetClass.getName(),
                            String.format(logMessageFormat, loggerParamWrapper.noneExceptionParams));
                    break;
                case Log.WARN:
                    Log.w(targetClass.getName(),
                            String.format(logMessageFormat, loggerParamWrapper.noneExceptionParams));
                    break;
                case Log.DEBUG:
                    Log.d(targetClass.getName(),
                            String.format(logMessageFormat, loggerParamWrapper.noneExceptionParams));
                    break;
                case Log.VERBOSE:
                    Log.v(targetClass.getName(),
                            String.format(logMessageFormat, loggerParamWrapper.noneExceptionParams));
                    break;
            }
        }
    }

    private <T> void concretePrintLogAutoDetectTargetClass(int level, Supplier<String> logSupplier,
                                                           Supplier<Object[]> argumentsSupplier) {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        String invokingClassName = this.findCurrentLoggerClassStackTraceIndex(stackTraceElements);
        Object[] arguments = new Object[]{};
        if (argumentsSupplier != null) {
            arguments = argumentsSupplier.get();
        }
        LoggerParamWrapper loggerParamWrapper = this.filterNoneExceptionParams(arguments);
        String logMessageFormat = logSupplier.get();
        Object exception = null;
        if (loggerParamWrapper.exceptionParams.length > 0) {
            exception = loggerParamWrapper.exceptionParams[0];
        }
        if (exception != null) {
            switch (level) {
                case Log.INFO:
                    Log.i(invokingClassName,
                            String.format(logMessageFormat, loggerParamWrapper.noneExceptionParams),
                            (Throwable) exception);
                    break;
                case Log.ERROR:
                    Log.e(invokingClassName,
                            String.format(logMessageFormat, loggerParamWrapper.noneExceptionParams),
                            (Throwable) exception);
                    break;
                case Log.WARN:
                    Log.w(invokingClassName,
                            String.format(logMessageFormat, loggerParamWrapper.noneExceptionParams),
                            (Throwable) exception);
                    break;
                case Log.DEBUG:
                    Log.d(invokingClassName,
                            String.format(logMessageFormat, loggerParamWrapper.noneExceptionParams),
                            (Throwable) exception);
                    break;
                case Log.VERBOSE:
                    Log.v(invokingClassName,
                            String.format(logMessageFormat, loggerParamWrapper.noneExceptionParams),
                            (Throwable) exception);
                    break;
            }
        } else {
            switch (level) {
                case Log.INFO:
                    Log.i(invokingClassName,
                            String.format(logMessageFormat, loggerParamWrapper.noneExceptionParams));
                    break;
                case Log.ERROR:
                    Log.e(invokingClassName,
                            String.format(logMessageFormat, loggerParamWrapper.noneExceptionParams));
                    break;
                case Log.WARN:
                    Log.w(invokingClassName,
                            String.format(logMessageFormat, loggerParamWrapper.noneExceptionParams));
                    break;
                case Log.DEBUG:
                    Log.d(invokingClassName,
                            String.format(logMessageFormat, loggerParamWrapper.noneExceptionParams));
                    break;
                case Log.VERBOSE:
                    Log.v(invokingClassName,
                            String.format(logMessageFormat, loggerParamWrapper.noneExceptionParams));
                    break;
            }
        }
    }

    public <T> void info(Class<T> targetClass, Supplier<String> logSupplier, Supplier<Object[]> argumentsSupplier) {
        this.concretePrintLogWithTargetClass(Log.INFO, targetClass, logSupplier, argumentsSupplier);
    }

    public <T> void info(Class<T> targetClass, Supplier<String> logSupplier) {
        this.concretePrintLogWithTargetClass(Log.INFO, targetClass, logSupplier, null);
    }

    public void info(Supplier<String> logSupplier) {
        this.concretePrintLogAutoDetectTargetClass(Log.INFO, logSupplier, null);
    }

    public void info(Supplier<String> logSupplier, Supplier<Object[]> argumentsSupplier) {
        this.concretePrintLogAutoDetectTargetClass(Log.INFO, logSupplier, argumentsSupplier);
    }

    public <T> void debug(Class<T> targetClass, Supplier<String> logSupplier, Supplier<Object[]> argumentsSupplier) {
        this.concretePrintLogWithTargetClass(Log.DEBUG, targetClass, logSupplier, argumentsSupplier);
    }

    public <T> void debug(Class<T> targetClass, Supplier<String> logSupplier) {
        this.concretePrintLogWithTargetClass(Log.DEBUG, targetClass, logSupplier, null);
    }

    public void debug(Supplier<String> logSupplier) {
        this.concretePrintLogAutoDetectTargetClass(Log.DEBUG, logSupplier, null);
    }

    public void debug(Supplier<String> logSupplier, Supplier<Object[]> argumentsSupplier) {
        this.concretePrintLogAutoDetectTargetClass(Log.DEBUG, logSupplier, argumentsSupplier);
    }

    public <T> void warn(Class<T> targetClass, Supplier<String> logSupplier, Supplier<Object[]> argumentsSupplier) {
        this.concretePrintLogWithTargetClass(Log.WARN, targetClass, logSupplier, argumentsSupplier);
    }

    public <T> void warn(Class<T> targetClass, Supplier<String> logSupplier) {
        this.concretePrintLogWithTargetClass(Log.WARN, targetClass, logSupplier, null);
    }

    public void warn(Supplier<String> logSupplier) {
        this.concretePrintLogAutoDetectTargetClass(Log.WARN, logSupplier, null);
    }

    public void warn(Supplier<String> logSupplier, Supplier<Object[]> argumentsSupplier) {
        this.concretePrintLogAutoDetectTargetClass(Log.WARN, logSupplier, argumentsSupplier);
    }

    public <T> void error(Class<T> targetClass, Supplier<String> logSupplier, Supplier<Object[]> argumentsSupplier) {
        this.concretePrintLogWithTargetClass(Log.ERROR, targetClass, logSupplier, argumentsSupplier);
    }

    public <T> void error(Class<T> targetClass, Supplier<String> logSupplier) {
        this.concretePrintLogWithTargetClass(Log.ERROR, targetClass, logSupplier, null);
    }

    public void error(Supplier<String> logSupplier) {
        this.concretePrintLogAutoDetectTargetClass(Log.ERROR, logSupplier, null);
    }

    public void error(Supplier<String> logSupplier, Supplier<Object[]> argumentsSupplier) {
        this.concretePrintLogAutoDetectTargetClass(Log.ERROR, logSupplier, argumentsSupplier);
    }

    public <T> void trace(Class<T> targetClass, Supplier<String> logSupplier, Supplier<Object[]> argumentsSupplier) {
        this.concretePrintLogWithTargetClass(Log.VERBOSE, targetClass, logSupplier, argumentsSupplier);
    }

    public <T> void trace(Class<T> targetClass, Supplier<String> logSupplier) {
        this.concretePrintLogWithTargetClass(Log.VERBOSE, targetClass, logSupplier, null);
    }

    public void trace(Supplier<String> logSupplier) {
        this.concretePrintLogAutoDetectTargetClass(Log.VERBOSE, logSupplier, null);
    }

    public void trace(Supplier<String> logSupplier, Supplier<Object[]> argumentsSupplier) {
        this.concretePrintLogAutoDetectTargetClass(Log.VERBOSE, logSupplier, argumentsSupplier);
    }
}
