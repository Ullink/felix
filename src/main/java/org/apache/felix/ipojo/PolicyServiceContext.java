/* 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.ipojo;

import java.util.Dictionary;

import org.apache.felix.ipojo.composite.ServiceReferenceImpl;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;

/**
 * The policy service context is a service context aiming to solve service requirement.
 * It's parameterized by a resolving policy. Three policies are managed : 
 * - Local : services are only solve un the local service registry
 * - Global : services are resolved only in the global (i.e. OSGi) service registry
 * - Local and Global : services are resolved inside the local registry and the global registry  
 * @author <a href="mailto:dev@felix.apache.org">Felix Project Team</a>
 */
public class PolicyServiceContext implements ServiceContext {
    
    /**
     * Resolving policy, look only in the composite.
     */
    public static final int LOCAL = 0;
    
    /**
     * Resolving policy, look inside the composite and in the global scope.
     * This policy is the default one for implementation dependency.
     */
    public static final int LOCAL_AND_GLOBAL = 1;
    
    /**
     * Resolving policy, look inside the global only.
     */
    public static final int GLOBAL = 2;
    
    /**
     * Global service registry.
     */
    private BundleContext m_global;
    
    /**
     * Local (Composite) Service Registry.
     */
    private ServiceContext m_local;
    
    /**
     * Resolving policy.
     */
    private int m_policy = LOCAL_AND_GLOBAL;
    
    
    /**
     * Create a new PolicyServiceContext.
     * @param global : global bundle context
     * @param local : parent (local) service context
     * @param policy : resolution policy
     */
    public PolicyServiceContext(BundleContext global, ServiceContext local, int policy) {
        m_global = global;
        m_local = local;
        m_policy = policy;
    }

    /**
     * Add a service listener according to the policy.
     * @param listener : the listener to add
     * @param filter : LDAP filter
     * @throws InvalidSyntaxException occurs when the filter is malformed.
     * @see org.apache.felix.ipojo.ServiceContext#addServiceListener(org.osgi.framework.ServiceListener, java.lang.String)
     */
    public void addServiceListener(ServiceListener listener, String filter) throws InvalidSyntaxException {
        if (m_policy == LOCAL || m_policy == LOCAL_AND_GLOBAL) {
            m_local.addServiceListener(listener, filter);
        }
        if (m_policy == GLOBAL || m_policy == LOCAL_AND_GLOBAL) {
            m_global.addServiceListener(listener, filter);
        }

    }

    /**
     * Add a service listener according to the policy.
     * @param listener : the listener to add
     * @see org.apache.felix.ipojo.ServiceContext#addServiceListener(org.osgi.framework.ServiceListener)
     */
    public void addServiceListener(ServiceListener listener) {
        if (m_policy == LOCAL || m_policy == LOCAL_AND_GLOBAL) {
            m_local.addServiceListener(listener);
        }
        if (m_policy == GLOBAL || m_policy == LOCAL_AND_GLOBAL) {
            m_global.addServiceListener(listener);
        }
    }

    /**
     * Get all service references. These reference are found inside the local registry, global registry or both according to the policy.
     * @param clazz : required service specification.
     * @param filter : LDAP filter
     * @return the array of service reference, null if no service available
     * @throws InvalidSyntaxException occurs when the LDAP filter is malformed 
     * @see org.apache.felix.ipojo.ServiceContext#getAllServiceReferences(java.lang.String, java.lang.String)
     */
    public ServiceReference[] getAllServiceReferences(String clazz,
            String filter) throws InvalidSyntaxException {
        switch (m_policy) {
            case LOCAL:
                return m_local.getAllServiceReferences(clazz, filter);
            case GLOBAL:
                return m_global.getAllServiceReferences(clazz, filter);
            case LOCAL_AND_GLOBAL:
                ServiceReference[] refLocal = m_local.getAllServiceReferences(clazz, filter);
                ServiceReference[] refGlobal = m_global.getAllServiceReferences(clazz, filter);
                if (refLocal != null && refGlobal != null) {
                    ServiceReference[] refs = new ServiceReference[refLocal.length + refGlobal.length];
                    int j = 0;
                    for (int i = 0; i < refLocal.length; i++) {
                        refs[j] = refLocal[i];
                        j++;
                    }
                    for (int i = 0; i < refGlobal.length; i++) {
                        refs[j] = refGlobal[i];
                        j++;
                    }
                    return refs;
                } else if (refLocal != null && refGlobal == null) {
                    return refLocal;
                } else {
                    return refGlobal;
                }
            default:
                return null;
        }
    }

    /**
     * Get the service object for the given reference.
     * @param ref : the service reference
     * @return the service object
     * @see org.apache.felix.ipojo.ServiceContext#getService(org.osgi.framework.ServiceReference)
     */
    public Object getService(ServiceReference ref) {
        switch(m_policy) {
            case LOCAL:
                // The reference comes from the local scope
                return m_local.getService(ref);
            case GLOBAL:
                // The reference comes from the global registry
                return m_global.getService(ref);
            case LOCAL_AND_GLOBAL:
                if (ref instanceof org.apache.felix.ipojo.composite.ServiceReferenceImpl) {
                    // The reference comes from a composite, i.e. necessary the local composite
                    return m_local.getService(ref);
                } else {
                    return m_global.getService(ref);
                }
            default : 
                return null;
        }
    }

    /**
     * Get a service reference for the required service specification.
     * @param clazz : the required service specification
     * @return a service reference or null if not consistent service available
     * @see org.apache.felix.ipojo.ServiceContext#getServiceReference(java.lang.String)
     */
    public ServiceReference getServiceReference(String clazz) {
        switch (m_policy) {
            case LOCAL:
                return m_local.getServiceReference(clazz);
            case GLOBAL:
                return m_global.getServiceReference(clazz);
            case LOCAL_AND_GLOBAL:
                ServiceReference refLocal = m_local.getServiceReference(clazz);
                if (refLocal != null) {
                    return refLocal;
                } else {
                    return m_global.getServiceReference(clazz); 
                }
            default:
                return null;
        }
    }

    /**
     * Get a service reference for the required service specification.
     * @param clazz : the required service specification
     * @param filter : LDAP filter
     * @return a service reference array or null if not consistent service available
     * @throws InvalidSyntaxException occurs when the LDAP filter is malformed 
     * @see org.apache.felix.ipojo.ServiceContext#getServiceReference(java.lang.String)
     */
    public ServiceReference[] getServiceReferences(String clazz, String filter) throws InvalidSyntaxException {
        switch (m_policy) {
            case LOCAL:
                return m_local.getServiceReferences(clazz, filter);
            case GLOBAL:
                return m_global.getServiceReferences(clazz, filter);
            case LOCAL_AND_GLOBAL:
                ServiceReference[] refLocal = m_local.getServiceReferences(clazz, filter);
                ServiceReference[] refGlobal = m_global.getServiceReferences(clazz, filter);
                if (refLocal != null && refGlobal != null) {
                    ServiceReference[] refs = new ServiceReference[refLocal.length + refGlobal.length];
                    int j = 0;
                    for (int i = 0; i < refLocal.length; i++) {
                        refs[j] = refLocal[i];
                        j++;
                    }
                    for (int i = 0; i < refGlobal.length; i++) {
                        refs[j] = refGlobal[i];
                        j++;
                    }
                    return refs;
                } else if (refLocal != null && refGlobal == null) {
                    return refLocal;
                } else {
                    return refGlobal;
                }
            default:
                return null;
        }

    }

    /**
     * This method is not supported.
     * @param clazzes : specifications
     * @param service : service object
     * @param properties : service properties
     * @return : the service registration object
     * @see org.apache.felix.ipojo.ServiceContext#registerService(java.lang.String[], java.lang.Object, java.util.Dictionary)
     */
    public ServiceRegistration registerService(String[] clazzes, Object service, Dictionary properties) {
        throw new UnsupportedOperationException("PolicyServiceContext can only be used for service dependency and not service providing");
    }

    /**
     * This method is not supported.
     * @param clazz : specification
     * @param service : service object
     * @param properties : service properties
     * @return : the service registration object
     * @see org.apache.felix.ipojo.ServiceContext#registerService(java.lang.String, java.lang.Object, java.util.Dictionary)
     */
    public ServiceRegistration registerService(String clazz, Object service, Dictionary properties) {
        throw new UnsupportedOperationException("PolicyServiceContext can only be used for service dependency and not service providing");
    }

    /**
     * Remove a service listener.
     * @param listener : the service listener to remove
     * @see org.apache.felix.ipojo.ServiceContext#removeServiceListener(org.osgi.framework.ServiceListener)
     */
    public void removeServiceListener(ServiceListener listener) {
        if (m_policy == LOCAL || m_policy == LOCAL_AND_GLOBAL) {
            m_local.removeServiceListener(listener);
        }
        if (m_policy == GLOBAL || m_policy == LOCAL_AND_GLOBAL) {
            m_global.removeServiceListener(listener);
        }
    }

    /**
     * Unget the service reference.
     * @param reference : the service reference to unget.
     * @return true if the unget is successful.
     * @see org.apache.felix.ipojo.ServiceContext#ungetService(org.osgi.framework.ServiceReference)
     */
    public boolean ungetService(ServiceReference reference) {
        if (reference instanceof ServiceReferenceImpl) {
            return m_local.ungetService(reference);
        } else {
            return m_global.ungetService(reference);
        }
    }

}
