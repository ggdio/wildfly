/*
 * JBoss, Home of Professional Open Source
 * Copyright (c) 2010, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.ejb3.tx;

import static org.jboss.as.ejb3.tx.util.StatusHelper.statusAsString;

import java.rmi.RemoteException;
import java.util.Random;

import javax.ejb.EJBException;
import javax.ejb.EJBTransactionRolledbackException;
import javax.ejb.NoSuchEJBException;
import javax.ejb.NoSuchEntityException;
import javax.ejb.TransactionAttributeType;
import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;

import org.jboss.as.ee.component.Component;
import org.jboss.as.ejb3.EjbLogger;
import org.jboss.as.ejb3.EjbMessages;
import org.jboss.as.ejb3.component.EJBComponent;
import org.jboss.as.ejb3.component.MethodIntf;
import org.jboss.as.ejb3.component.MethodIntfHelper;
import org.jboss.invocation.ImmediateInterceptorFactory;
import org.jboss.invocation.Interceptor;
import org.jboss.invocation.InterceptorContext;
import org.jboss.invocation.InterceptorFactory;
import org.jboss.tm.TransactionTimeoutConfiguration;
import org.jboss.util.deadlock.ApplicationDeadlockException;

/**
 * Ensure the correct exceptions are thrown based on both caller
 * transactional context and supported Transaction Attribute Type
 * <p/>
 * EJB3 13.6.2.6
 * EJB3 Core Specification 14.3.1 Table 14
 *
 * @author <a href="mailto:andrew.rubinger@redhat.com">ALR</a>
 * @author <a href="mailto:cdewolf@redhat.com">Carlo de Wolf</a>
 * @author Scott Marlow
 */
public class CMTTxInterceptor implements Interceptor {

    private static final int MAX_RETRIES = 5;
    private static final Random RANDOM = new Random();

    public static final InterceptorFactory FACTORY = new ImmediateInterceptorFactory(new CMTTxInterceptor());


    /**
     * The <code>endTransaction</code> method ends a transaction and
     * translates any exceptions into
     * TransactionRolledBack[Local]Exception or SystemException.
     *
     * @param tm a <code>TransactionManager</code> value
     * @param tx a <code>Transaction</code> value
     */
    protected void endTransaction(final TransactionManager tm, final Transaction tx) {
        try {
            if (tx != tm.getTransaction()) {
                throw EjbMessages.MESSAGES.wrongTxOnThread(tx, tm.getTransaction());
            }
            final int txStatus = tx.getStatus();
            if (txStatus == Status.STATUS_ACTIVE) {
                // Commit tx
                // This will happen if
                // a) everything goes well
                // b) app. exception was thrown
                tm.commit();
            } else if (txStatus == Status.STATUS_MARKED_ROLLBACK) {
                tm.rollback();
            } else if (txStatus == Status.STATUS_ROLLEDBACK) {
                // handle reaper canceled (rolled back) tx case (see WFLY-1346)
                // clear current tx state and throw RollbackException (EJBTransactionRolledbackException)
                tm.suspend();
                throw EjbMessages.MESSAGES.transactionAlreadyRolledBack(tx);
            } else if (txStatus == Status.STATUS_UNKNOWN) {
                // STATUS_UNKNOWN isn't expected to be reached here but if it does, we need to clear current thread tx.
                // It is possible that calling tm.commit() could succeed but we call tm.rollback, since this is an unexpected
                // tx state that are are handling.
                tm.rollback();
                // if the tm.rollback doesn't fail, we throw an EJBException to reflect the unexpected tx state.
                throw EjbMessages.MESSAGES.transactionInUnexpectedState(tx, statusAsString(txStatus));
            } else {
                // logically, all of the following (unexpected) tx states are handled here:
                //  Status.STATUS_PREPARED
                //  Status.STATUS_PREPARING
                //  Status.STATUS_ROLLING_BACK
                //  Status.STATUS_NO_TRANSACTION
                //  Status.STATUS_COMMITTED
                tm.suspend();                       // clear current tx state and throw EJBException
                throw EjbMessages.MESSAGES.transactionInUnexpectedState(tx, statusAsString(txStatus));
            }
        } catch (RollbackException e) {
            handleEndTransactionException(e);
        } catch (HeuristicMixedException e) {
            handleEndTransactionException(e);
        } catch (HeuristicRollbackException e) {
            handleEndTransactionException(e);
        } catch (SystemException e) {
            handleEndTransactionException(e);
        }
    }

    protected int getCurrentTransactionTimeout(final EJBComponent component) throws SystemException {
        final TransactionManager tm = component.getTransactionManager();
        if (tm instanceof TransactionTimeoutConfiguration) {
            return ((TransactionTimeoutConfiguration) tm).getTransactionTimeout();
        }
        return 0;
    }

    protected void handleEndTransactionException(Exception e) {
        if (e instanceof RollbackException)
            throw new EJBTransactionRolledbackException("Transaction rolled back", e);
        throw new EJBException(e);
    }

    protected void handleInCallerTx(InterceptorContext invocation, Throwable t, Transaction tx, final EJBComponent component) throws Exception {
        ApplicationExceptionDetails ae = component.getApplicationException(t.getClass(), invocation.getMethod());

        if (ae != null) {
            if (ae.isRollback()) setRollbackOnly(tx);
            // an app exception can never be an Error
            throw (Exception) t;
        }

        // if it's not EJBTransactionRolledbackException
        if (!(t instanceof EJBTransactionRolledbackException)) {
            if (t instanceof Error) {
                //t = new EJBTransactionRolledbackException(formatException("Unexpected Error", t));
                Throwable cause = t;
                t = new EJBTransactionRolledbackException("Unexpected Error");
                t.initCause(cause);
            } else if (t instanceof NoSuchEJBException || t instanceof NoSuchEntityException) {
                // If this is a NoSuchEJBException, pass through to the caller

            } else if (t instanceof RuntimeException) {
                t = new EJBTransactionRolledbackException(t.getMessage(), (Exception) t);
            } else {// application exception
                throw (Exception) t;
            }
        }

        setRollbackOnly(tx);
        EjbLogger.ROOT_LOGGER.error(t);
        throw (Exception) t;
    }

    public void handleExceptionInOurTx(InterceptorContext invocation, Throwable t, Transaction tx, final EJBComponent component) throws Exception {
        ApplicationExceptionDetails ae = component.getApplicationException(t.getClass(), invocation.getMethod());
        if (ae != null) {
            if (ae.isRollback()) setRollbackOnly(tx);
            throw (Exception) t;
        }

        // if it's neither EJBException nor RemoteException
        if (!(t instanceof EJBException || t instanceof RemoteException)) {
            // errors and unchecked are wrapped into EJBException
            if (t instanceof Error) {
                //t = new EJBException(formatException("Unexpected Error", t));
                Throwable cause = t;
                t = EjbMessages.MESSAGES.unexpectedError();
                t.initCause(cause);
            } else if (t instanceof RuntimeException) {
                t = new EJBException((Exception) t);
            } else {
                // an application exception
                throw (Exception) t;
            }
        }

        setRollbackOnly(tx);
        throw (Exception) t;
    }

    public void handleExceptionInNoTx(InterceptorContext invocation, Throwable t, final EJBComponent component) throws Exception {
        ApplicationExceptionDetails ae = component.getApplicationException(t.getClass(), invocation.getMethod());
        if (ae != null) {
            throw (Exception) t;
        }

        // if it's neither EJBException nor RemoteException
        if (!(t instanceof EJBException || t instanceof RemoteException)) {
            // errors and unchecked are wrapped into EJBException
            if (t instanceof Error) {
                //t = new EJBException(formatException("Unexpected Error", t));
                Throwable cause = t;
                t = EjbMessages.MESSAGES.unexpectedError();
                t.initCause(cause);
            } else if (t instanceof RuntimeException) {
                t = new EJBException((Exception) t);
            } else {
                // an application exception
                throw (Exception) t;
            }
        }

        throw (Exception) t;
    }

    public Object processInvocation(InterceptorContext invocation) throws Exception {
        final EJBComponent component = (EJBComponent) invocation.getPrivateData(Component.class);
        final MethodIntf methodIntf = MethodIntfHelper.of(invocation);
        final TransactionAttributeType attr = component.getTransactionAttributeType(methodIntf, invocation.getMethod());
        final int timeoutInSeconds = component.getTransactionTimeout(methodIntf, invocation.getMethod());
        switch (attr) {
            case MANDATORY:
                return mandatory(invocation, component);
            case NEVER:
                return never(invocation, component);
            case NOT_SUPPORTED:
                return notSupported(invocation, component);
            case REQUIRED:
                return required(invocation, component, timeoutInSeconds);
            case REQUIRES_NEW:
                return requiresNew(invocation, component, timeoutInSeconds);
            case SUPPORTS:
                return supports(invocation, component);
            default:
                throw EjbMessages.MESSAGES.unknownTxAttributeOnInvocation(attr, invocation);
        }
    }

    protected Object invokeInCallerTx(InterceptorContext invocation, Transaction tx, final EJBComponent component) throws Exception {
        try {
            return invocation.proceed();
        } catch (Throwable t) {
            handleInCallerTx(invocation, t, tx, component);
        }
        throw new RuntimeException("UNREACHABLE");
    }

    protected Object invokeInNoTx(InterceptorContext invocation, final EJBComponent component) throws Exception {
        try {
            return invocation.proceed();
        } catch (Throwable t) {
            handleExceptionInNoTx(invocation, t, component);
        }
        throw new RuntimeException("UNREACHABLE");
    }

    protected Object invokeInOurTx(InterceptorContext invocation, TransactionManager tm, final EJBComponent component) throws Exception {
        for (int i = 0; i < MAX_RETRIES; i++) {
            tm.begin();
            Transaction tx = tm.getTransaction();
            try {
                try {
                    return invocation.proceed();
                } catch (Throwable t) {
                    handleExceptionInOurTx(invocation, t, tx, component);
                } finally {
                    endTransaction(tm, tx);
                }
            } catch (Exception ex) {
                ApplicationDeadlockException deadlock = ApplicationDeadlockException.isADE(ex);
                if (deadlock != null) {
                    if (!deadlock.retryable() || i + 1 >= MAX_RETRIES) {
                        throw deadlock;
                    }
                    EjbLogger.ROOT_LOGGER.retrying(deadlock.getLocalizedMessage(), (i + 1));

                    Thread.sleep(RANDOM.nextInt(1 + i), RANDOM.nextInt(1000));
                } else {
                    throw ex;
                }
            }
        }
        throw new RuntimeException("UNREACHABLE");
    }

    protected Object mandatory(InterceptorContext invocation, final EJBComponent component) throws Exception {
        final TransactionManager tm = component.getTransactionManager();
        Transaction tx = tm.getTransaction();
        if (tx == null) {
            throw EjbMessages.MESSAGES.txRequiredForInvocation(invocation);
        }
        return invokeInCallerTx(invocation, tx, component);
    }

    protected Object never(InterceptorContext invocation, final EJBComponent component) throws Exception {
        final TransactionManager tm = component.getTransactionManager();
        if (tm.getTransaction() != null) {
            throw EjbMessages.MESSAGES.txPresentForNeverTxAttribute();
        }
        return invokeInNoTx(invocation, component);
    }

    protected Object notSupported(InterceptorContext invocation, final EJBComponent component) throws Exception {
        final TransactionManager tm = component.getTransactionManager();
        Transaction tx = tm.getTransaction();
        if (tx != null) {
            tm.suspend();
            try {
                return invokeInNoTx(invocation, component);
            } finally {
                tm.resume(tx);
            }
        } else {
            return invokeInNoTx(invocation, component);
        }
    }

    protected Object required(final InterceptorContext invocation, final EJBComponent component, final int timeout) throws Exception {
        final TransactionManager tm = component.getTransactionManager();
        final int oldTimeout = getCurrentTransactionTimeout(component);

        try {
            if (timeout != -1) {
                tm.setTransactionTimeout(timeout);
            }

            final Transaction tx = tm.getTransaction();

            if (tx == null) {
                return invokeInOurTx(invocation, tm, component);
            } else {
                return invokeInCallerTx(invocation, tx, component);
            }
        } finally {
            if (tm != null) {
                tm.setTransactionTimeout(oldTimeout);
            }
        }
    }

    protected Object requiresNew(InterceptorContext invocation, final EJBComponent component, final int timeout) throws Exception {
        final TransactionManager tm = component.getTransactionManager();
        int oldTimeout = getCurrentTransactionTimeout(component);

        try {
            if (timeout != -1 && tm != null) {
                tm.setTransactionTimeout(timeout);
            }

            Transaction tx = tm.getTransaction();
            if (tx != null) {
                tm.suspend();
                try {
                    return invokeInOurTx(invocation, tm, component);
                } finally {
                    tm.resume(tx);
                }
            } else {
                return invokeInOurTx(invocation, tm, component);
            }
        } finally {
            if (tm != null) {
                tm.setTransactionTimeout(oldTimeout);
            }
        }
    }

    /**
     * The <code>setRollbackOnly</code> method calls setRollbackOnly()
     * on the invocation's transaction and logs any exceptions than may
     * occur.
     *
     * @param tx the transaction
     */
    protected void setRollbackOnly(Transaction tx) {
        try {
            tx.setRollbackOnly();
        } catch (SystemException ex) {
            EjbLogger.EJB3_LOGGER.failedToSetRollbackOnly(ex);
        } catch (IllegalStateException ex) {
            EjbLogger.EJB3_LOGGER.failedToSetRollbackOnly(ex);
        }
    }

    protected Object supports(InterceptorContext invocation, final EJBComponent component) throws Exception {
        final TransactionManager tm = component.getTransactionManager();
        Transaction tx = tm.getTransaction();
        if (tx == null) {
            return invokeInNoTx(invocation, component);
        } else {
            return invokeInCallerTx(invocation, tx, component);
        }
    }
}
