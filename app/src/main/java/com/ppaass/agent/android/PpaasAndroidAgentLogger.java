package com.ppaass.agent.android;

import android.util.Log;
import com.ppaass.common.log.IPpaassLogger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

public class PpaasAndroidAgentLogger implements IPpaassLogger {

    public PpaasAndroidAgentLogger() {
    }

    private

    private String findCurrentLoggerClassStackTraceIndex(StackTraceElement[] stackTraceElements) {
        for (int i = 0; i < stackTraceElements.length; i++) {
            if (stackTraceElements[i].getClassName().equals(PpaasAndroidAgentLogger.class.getName())) {
                return stackTraceElements[i + 1].getClassName();
            }
        }
        return PpaasAndroidAgentLogger.class.getName();
    }

    private Object[] filterNoneExceptionParams(Object[] params){
        List<Object> noneExceptionParam = new ArrayList<>();
        List<Object> exceptionParam = new ArrayList<>();
        for(Object param: params){
            if(param instanceof Exception){
                noneExceptionParam.add(param);
            }else{
                exceptionParam.add(param);
            }
        }

    }

    public <T> void info(Class<T> targetClass, Supplier<String> logSupplier, Supplier<Object[]> argumentsSupplier) {


        if (logger.isInfoEnabled()) {
            Log.i(targetClass.getName(), logSupplier.get(), argumentsSupplier.get());
        }
    }

    public <T> void info(Class<T> targetClass, Supplier<String> logSupplier) {
        Logger logger = loggers.computeIfAbsent(targetClass.getName(), LoggerFactory::getLogger);
        if (logger.isInfoEnabled()) {
            logger.info(logSupplier.get());
        }
    }

    public void info(Supplier<String> logSupplier) {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        String invokingClassName = this.findCurrentLoggerClassStackTraceIndex(stackTraceElements);
        Logger logger = loggers.computeIfAbsent(invokingClassName, LoggerFactory::getLogger);
        if (logger.isInfoEnabled()) {
            logger.info(logSupplier.get());
        }
    }

    public void info(Supplier<String> logSupplier, Supplier<Object[]> argumentsSupplier) {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        String invokingClassName = this.findCurrentLoggerClassStackTraceIndex(stackTraceElements);
        Logger logger = loggers.computeIfAbsent(invokingClassName, LoggerFactory::getLogger);
        if (logger.isInfoEnabled()) {
            logger.info(logSupplier.get(), argumentsSupplier.get());
        }
    }

    public <T> void debug(Class<T> targetClass, Supplier<String> logSupplier, Supplier<Object[]> argumentsSupplier) {
        Logger logger = loggers.computeIfAbsent(targetClass.getName(), LoggerFactory::getLogger);
        if (logger.isDebugEnabled()) {
            logger.debug(logSupplier.get(), argumentsSupplier.get());
        }
    }

    public <T> void debug(Class<T> targetClass, Supplier<String> logSupplier) {
        Logger logger = loggers.computeIfAbsent(targetClass.getName(), LoggerFactory::getLogger);
        if (logger.isDebugEnabled()) {
            logger.debug(logSupplier.get());
        }
    }

    public void debug(Supplier<String> logSupplier) {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        String invokingClassName = this.findCurrentLoggerClassStackTraceIndex(stackTraceElements);
        Logger logger = loggers.computeIfAbsent(invokingClassName, LoggerFactory::getLogger);
        if (logger.isDebugEnabled()) {
            logger.debug(logSupplier.get());
        }
    }

    public void debug(Supplier<String> logSupplier, Supplier<Object[]> argumentsSupplier) {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        String invokingClassName = this.findCurrentLoggerClassStackTraceIndex(stackTraceElements);
        Logger logger = loggers.computeIfAbsent(invokingClassName, LoggerFactory::getLogger);
        if (logger.isDebugEnabled()) {
            logger.debug(logSupplier.get(), argumentsSupplier.get());
        }
    }

    public <T> void warn(Class<T> targetClass, Supplier<String> logSupplier, Supplier<Object[]> argumentsSupplier) {
        Logger logger = loggers.computeIfAbsent(targetClass.getName(), LoggerFactory::getLogger);
        if (logger.isWarnEnabled()) {
            logger.warn(logSupplier.get(), argumentsSupplier.get());
        }
    }

    public <T> void warn(Class<T> targetClass, Supplier<String> logSupplier) {
        Logger logger = loggers.computeIfAbsent(targetClass.getName(), LoggerFactory::getLogger);
        if (logger.isWarnEnabled()) {
            logger.warn(logSupplier.get());
        }
    }

    public void warn(Supplier<String> logSupplier) {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        String invokingClassName = this.findCurrentLoggerClassStackTraceIndex(stackTraceElements);
        Logger logger = loggers.computeIfAbsent(invokingClassName, LoggerFactory::getLogger);
        if (logger.isWarnEnabled()) {
            logger.warn(logSupplier.get());
        }
    }

    public void warn(Supplier<String> logSupplier, Supplier<Object[]> argumentsSupplier) {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        String invokingClassName = this.findCurrentLoggerClassStackTraceIndex(stackTraceElements);
        Logger logger = loggers.computeIfAbsent(invokingClassName, LoggerFactory::getLogger);
        if (logger.isWarnEnabled()) {
            logger.warn(logSupplier.get(), argumentsSupplier.get());
        }
    }

    public <T> void error(Class<T> targetClass, Supplier<String> logSupplier, Supplier<Object[]> argumentsSupplier) {
        Logger logger = loggers.computeIfAbsent(targetClass.getName(), LoggerFactory::getLogger);
        if (logger.isErrorEnabled()) {
            logger.error(logSupplier.get(), argumentsSupplier.get());
        }
    }

    public <T> void error(Class<T> targetClass, Supplier<String> logSupplier) {
        Logger logger = loggers.computeIfAbsent(targetClass.getName(), LoggerFactory::getLogger);
        if (logger.isErrorEnabled()) {
            logger.error(logSupplier.get());
        }
    }

    public void error(Supplier<String> logSupplier) {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        String invokingClassName = this.findCurrentLoggerClassStackTraceIndex(stackTraceElements);
        Logger logger = loggers.computeIfAbsent(invokingClassName, LoggerFactory::getLogger);
        if (logger.isErrorEnabled()) {
            logger.error(logSupplier.get());
        }
    }

    public void error(Supplier<String> logSupplier, Supplier<Object[]> argumentsSupplier) {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        String invokingClassName = this.findCurrentLoggerClassStackTraceIndex(stackTraceElements);
        Logger logger = loggers.computeIfAbsent(invokingClassName, LoggerFactory::getLogger);
        if (logger.isErrorEnabled()) {
            logger.error(logSupplier.get(), argumentsSupplier.get());
        }
    }

    public <T> void trace(Class<T> targetClass, Supplier<String> logSupplier, Supplier<Object[]> argumentsSupplier) {
        Logger logger = loggers.computeIfAbsent(targetClass.getName(), LoggerFactory::getLogger);
        if (logger.isTraceEnabled()) {
            logger.trace(logSupplier.get(), argumentsSupplier.get());
        }
    }

    public <T> void trace(Class<T> targetClass, Supplier<String> logSupplier) {
        Logger logger = loggers.computeIfAbsent(targetClass.getName(), LoggerFactory::getLogger);
        if (logger.isTraceEnabled()) {
            logger.trace(logSupplier.get());
        }
    }

    public void trace(Supplier<String> logSupplier) {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        String invokingClassName = this.findCurrentLoggerClassStackTraceIndex(stackTraceElements);
        Logger logger = loggers.computeIfAbsent(invokingClassName, LoggerFactory::getLogger);
        if (logger.isTraceEnabled()) {
            logger.trace(logSupplier.get());
        }
    }

    public void trace(Supplier<String> logSupplier, Supplier<Object[]> argumentsSupplier) {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        String invokingClassName = this.findCurrentLoggerClassStackTraceIndex(stackTraceElements);
        Logger logger = loggers.computeIfAbsent(invokingClassName, LoggerFactory::getLogger);
        if (logger.isTraceEnabled()) {
            logger.trace(logSupplier.get(), argumentsSupplier.get());
        }
    }
}
