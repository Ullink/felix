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
package org.apache.felix.scrplugin;

import java.io.File;
import java.util.*;

import org.apache.felix.scrplugin.om.*;
import org.apache.felix.scrplugin.om.metatype.*;
import org.apache.felix.scrplugin.tags.*;
import org.apache.felix.scrplugin.xml.ComponentDescriptorIO;
import org.apache.felix.scrplugin.xml.MetaTypeIO;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.*;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.StringUtils;
import org.osgi.service.metatype.MetaTypeService;

/**
 * The <code>SCRDescriptorMojo</code>
 * generates a service descriptor file based on annotations found in the sources.
 *
 * @goal scr
 * @phase process-classes
 * @description Build Service Descriptors from Java Source
 * @requiresDependencyResolution compile
 */
public class SCRDescriptorMojo extends AbstractMojo {

    /**
     * @parameter expression="${project.build.directory}/scr-plugin-generated"
     * @required
     * @readonly
     */
    private File outputDirectory;

    /**
     * The Maven project.
     *
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;

    /**
     * Name of the generated descriptor.
     *
     * @parameter expression="${scr.descriptor.name}" default-value="serviceComponents.xml"
     */
    private String finalName;

    /**
     * Name of the generated meta type file.
     *
     * @parameter default-value="metatype.xml"
     */
    private String metaTypeName;

    /**
     * This flag controls the generation of the bind/unbind methods.
     * @parameter default-value="true"
     */
    private boolean generateAccessors;

    /**
     * The comma separated list of tokens to exclude when processing sources.
     *
     * @parameter alias="excludes"
     */
    private String sourceExcludes;

    /**
     * @see org.apache.maven.plugin.AbstractMojo#execute()
     */
    public void execute() throws MojoExecutionException, MojoFailureException {
        this.getLog().debug("Starting SCRDescriptorMojo....");
        this.getLog().debug("..generating accessors: " + this.generateAccessors);

        boolean hasFailures = false;

        JavaClassDescriptorManager jManager = new JavaClassDescriptorManager(this.getLog(),
                                                                             this.project,
                                                                             this.sourceExcludes);
        // iterate through all source classes and check for component tag
        final JavaClassDescription[] javaSources = jManager.getSourceDescriptions();
        Arrays.sort(javaSources, new JavaClassDescriptionInheritanceComparator());

        final Components components = new Components();
        final Components abstractComponents = new Components();
        final MetaData metaData = new MetaData();
        metaData.setLocalization(MetaTypeService.METATYPE_DOCUMENTS_LOCATION + "/metatype");

        for (int i = 0; i < javaSources.length; i++) {
            this.getLog().debug("Testing source " + javaSources[i].getName());
            final JavaTag tag = javaSources[i].getTagByName(Constants.COMPONENT);
            if (tag != null) {
                this.getLog().debug("Processing service class " + javaSources[i].getName());
                final Component comp = this.createComponent(javaSources[i], tag, metaData);
                if (comp != null) {
                    if ( !comp.isDs() ) {
                        getLog().debug("Not adding descriptor " + comp);
                    } else if ( comp.isAbstract() ) {
                        this.getLog().debug("Adding abstract descriptor " + comp);
                        abstractComponents.addComponent(comp);
                    } else {
                        this.getLog().debug("Adding descriptor " + comp);
                        components.addComponent(comp);
                        abstractComponents.addComponent(comp);
                    }
                } else {
                    hasFailures = true;
                }
            }
        }

        // after checking all classes, throw if there were any failures
        if (hasFailures) {
            throw new MojoFailureException("SCR Descriptor parsing had failures (see log)");
        }

        boolean addResources = false;
        // write meta type info if there is a file name
        if (!StringUtils.isEmpty(this.metaTypeName)) {
            File mtFile = new File(this.outputDirectory, "OSGI-INF" + File.separator + "metatype" + File.separator + this.metaTypeName);
            if ( metaData.getDescriptors().size() > 0 ) {
                this.getLog().info("Generating "
                    + metaData.getDescriptors().size()
                    + " MetaType Descriptors to " + mtFile);
                mtFile.getParentFile().mkdirs();
                MetaTypeIO.write(metaData, mtFile);
                addResources = true;
            } else {
                if ( mtFile.exists() ) {
                    mtFile.delete();
                }
            }

        } else {
            this.getLog().info("Meta type file name is not set: meta type info is not written.");
        }

        // if we have descriptors, write them in our scr private file (for component inheritance)
        final File adFile = new File(this.outputDirectory, Constants.ABSTRACT_DESCRIPTOR_RELATIVE_PATH);
        if ( !abstractComponents.getComponents().isEmpty() ) {
            this.getLog().info("Writing abstract service descriptor " + adFile + " with " + abstractComponents.getComponents().size() + " entries.");
            adFile.getParentFile().mkdirs();
            ComponentDescriptorIO.write(abstractComponents, adFile, true);
            addResources = true;
        } else {
            this.getLog().debug("No abstract SCR Descriptors found in project.");
            // remove file
            if ( adFile.exists() ) {
                this.getLog().debug("Removing obsolete abstract service descriptor " + adFile);
                adFile.delete();
            }
        }

        // check descriptor file
        final File descriptorFile = StringUtils.isEmpty(this.finalName) ? null : new File(new File(this.outputDirectory, "OSGI-INF"), this.finalName);

        // terminate if there is nothing else to write
        if (components.getComponents().isEmpty()) {
            this.getLog().debug("No SCR Descriptors found in project.");
            // remove file if it exists
            if ( descriptorFile != null && descriptorFile.exists() ) {
                this.getLog().debug("Removing obsolete service descriptor " + descriptorFile);
                descriptorFile.delete();
            }
        } else {

            if (descriptorFile == null) {
                throw new MojoFailureException("Descriptor file name must not be empty.");
            }

            // finally the descriptors have to be written ....
            descriptorFile.getParentFile().mkdirs(); // ensure parent dir

            this.getLog().info("Generating " + components.getComponents().size()
                    + " Service Component Descriptors to " + descriptorFile);

            ComponentDescriptorIO.write(components, descriptorFile, false);
            addResources = true;

            // and set include accordingly
            String svcComp = project.getProperties().getProperty("Service-Component");
            svcComp= (svcComp == null) ? "OSGI-INF/" + finalName : svcComp + ", " + "OSGI-INF/" + finalName;
            project.getProperties().setProperty("Service-Component", svcComp);
        }

        // now add the descriptor directory to the maven resources
        if (addResources) {
            final String ourRsrcPath = this.outputDirectory.getAbsolutePath();
            boolean found = false;
            final Iterator rsrcIterator = this.project.getResources().iterator();
            while ( !found && rsrcIterator.hasNext() ) {
                final Resource rsrc = (Resource)rsrcIterator.next();
                found = rsrc.getDirectory().equals(ourRsrcPath);
            }
            if ( !found ) {
                final Resource resource = new Resource();
                resource.setDirectory(this.outputDirectory.getAbsolutePath());
                this.project.addResource(resource);
            }
        }
    }

    /**
     * Create a component for the java class description.
     * @param description
     * @return The generated component descriptor or null if any error occurs.
     * @throws MojoExecutionException
     */
    protected Component createComponent(JavaClassDescription description, JavaTag componentTag, MetaData metaData)
    throws MojoExecutionException {
        // create a new component
        final Component component = new Component(componentTag);

        // set implementation
        component.setImplementation(new Implementation(description.getName()));

        final OCD ocd = this.doComponent(componentTag, component, metaData);

        boolean inherited = getBoolean(componentTag, Constants.COMPONENT_INHERIT, true);
        this.doServices(description.getTagsByName(Constants.SERVICE, inherited), component, description);

        // collect references from class tags and fields
        final Map references = new HashMap();
        // Utility handler for propertie
        final PropertyHandler propertyHandler = new PropertyHandler(component, ocd);

        JavaClassDescription currentDescription = description;
        do {
            // properties
            final JavaTag[] props = currentDescription.getTagsByName(Constants.PROPERTY, false);
            for (int i=0; i < props.length; i++) {
                propertyHandler.testProperty(props[i], null, description == currentDescription);
            }

            // references
            final JavaTag[] refs = currentDescription.getTagsByName(Constants.REFERENCE, false);
            for (int i=0; i < refs.length; i++) {
                this.testReference(references, refs[i], null, description == currentDescription);
            }

            // fields
            final JavaField[] fields = currentDescription.getFields();
            for (int i=0; i < fields.length; i++) {
                JavaTag tag = fields[i].getTagByName(Constants.REFERENCE);
                if (tag != null) {
                    this.testReference(references, tag, fields[i].getName(), description == currentDescription);
                }

                propertyHandler.handleField(fields[i], description == currentDescription);
            }

            currentDescription = currentDescription.getSuperClass();
        } while (inherited && currentDescription != null);

        // process properties
        propertyHandler.processProperties();

        // process references
        final Iterator refIter = references.entrySet().iterator();
        while ( refIter.hasNext() ) {
            final Map.Entry entry = (Map.Entry)refIter.next();
            final String refName = entry.getKey().toString();
            final JavaTag tag = (JavaTag)entry.getValue();
            this.doReference(tag, refName, component);
        }

        // pid handling
        final boolean createPid = getBoolean(componentTag, Constants.COMPONENT_CREATE_PID, true);
        if ( createPid ) {
            // check for an existing pid first
            boolean found = false;
            final Iterator iter = component.getProperties().iterator();
            while ( !found && iter.hasNext() ) {
                final Property prop = (Property)iter.next();
                found = org.osgi.framework.Constants.SERVICE_PID.equals( prop.getName() );
            }
            if ( !found ) {
                final Property pid = new Property();
                component.addProperty(pid);
                pid.setName(org.osgi.framework.Constants.SERVICE_PID);
                pid.setValue(component.getName());
            }
        }

        final List issues = new ArrayList();
        final List warnings = new ArrayList();
        component.validate(issues, warnings);

        // now log warnings and errors (warnings first)
        Iterator i = warnings.iterator();
        while ( i.hasNext() ) {
            this.getLog().warn((String)i.next());
        }
        i = issues.iterator();
        while ( i.hasNext() ) {
            this.getLog().error((String)i.next());
        }

        // return nothing if validation fails
        return issues.size() == 0 ? component : null;
    }

    /**
     * Fill the component object with the information from the tag.
     * @param tag
     * @param component
     */
    protected OCD doComponent(JavaTag tag, Component component, MetaData metaData) {

        // check if this is an abstract definition
        final String abstractType = tag.getNamedParameter(Constants.COMPONENT_ABSTRACT);
        if (abstractType != null) {
            component.setAbstract("yes".equalsIgnoreCase(abstractType) || "true".equalsIgnoreCase(abstractType));
        } else {
            // default true for abstract classes, false otherwise
            component.setAbstract(tag.getJavaClassDescription().isAbstract());
        }

        // check if this is a definition to ignore
        final String ds = tag.getNamedParameter(Constants.COMPONENT_DS);
        component.setDs((ds == null) ? true : ("yes".equalsIgnoreCase(ds) || "true".equalsIgnoreCase(ds)));

        String name = tag.getNamedParameter(Constants.COMPONENT_NAME);
        component.setName(StringUtils.isEmpty(name) ? component.getImplementation().getClassame() : name);

        component.setEnabled(Boolean.valueOf(getBoolean(tag, Constants.COMPONENT_ENABLED, true)));
        component.setFactory(tag.getNamedParameter(Constants.COMPONENT_FACTORY));

        // FELIX-593: immediate attribute does not default to true all the
        // times hence we only set it if declared in the tag
        if (tag.getNamedParameter(Constants.COMPONENT_IMMEDIATE) != null) {
            component.setImmediate(Boolean.valueOf(getBoolean(tag,
                Constants.COMPONENT_IMMEDIATE, true)));
        }

        // whether metatype information is to generated for the component
        final String metaType = tag.getNamedParameter(Constants.COMPONENT_METATYPE);
        final boolean hasMetaType = metaType == null || "yes".equalsIgnoreCase(metaType)
            || "true".equalsIgnoreCase(metaType);
        if ( hasMetaType ) {
            // ocd
            final OCD ocd = new OCD();
            metaData.addOCD(ocd);
            ocd.setId(component.getName());
            String ocdName = tag.getNamedParameter(Constants.COMPONENT_LABEL);
            if ( ocdName == null ) {
                ocdName = "%" + component.getName() + ".name";
            }
            ocd.setName(ocdName);
            String ocdDescription = tag.getNamedParameter(Constants.COMPONENT_DESCRIPTION);
            if ( ocdDescription == null ) {
                ocdDescription = "%" + component.getName() + ".description";
            }
            ocd.setDescription(ocdDescription);
            // designate
            final Designate designate = new Designate();
            metaData.addDesignate(designate);
            designate.setPid(component.getName());
            // designate.object
            final MTObject mtobject = new MTObject();
            designate.setObject(mtobject);
            mtobject.setOcdref(component.getName());
            return ocd;
        }
        return null;
    }

    /**
     * Process the service annotations
     * @param services
     * @param component
     * @param description
     * @throws MojoExecutionException
     */
    protected void doServices(JavaTag[] services, Component component, JavaClassDescription description)
    throws MojoExecutionException {
        // no services, hence certainly no service factory
        if (services == null || services.length == 0) {
            return;
        }

        final Service service = new Service();
        component.setService(service);
        boolean serviceFactory = false;
        for (int i=0; i < services.length; i++) {
            final String name = services[i].getNamedParameter(Constants.SERVICE_INTERFACE);
            if (StringUtils.isEmpty(name)) {

                this.addInterfaces(service, services[i], description);
            } else {
                // check if the value points to a class/interface
                // and search through the imports
                final JavaClassDescription serviceClass = description.getReferencedClass(name);
                if ( serviceClass == null ) {
                    throw new MojoExecutionException("Interface '"+ name + "' in class " + description.getName() + " does not point to a valid class/interface.");
                }
                final Interface interf = new Interface(services[i]);
                interf.setInterfacename(serviceClass.getName());
                service.addInterface(interf);
            }

            serviceFactory |= getBoolean(services[i], Constants.SERVICE_FACTORY, false);
        }

        service.setServicefactory(serviceFactory);
    }

    /**
     * Recursively add interfaces to the service.
     */
    protected void addInterfaces(final Service service, final JavaTag serviceTag, final JavaClassDescription description)
    throws MojoExecutionException {
        if ( description != null ) {
            JavaClassDescription[] interfaces = description.getImplementedInterfaces();
            for (int j=0; j < interfaces.length; j++) {
                final Interface interf = new Interface(serviceTag);
                interf.setInterfacename(interfaces[j].getName());
                service.addInterface(interf);
                // recursivly add interfaces implemented by this interface
                this.addInterfaces(service, serviceTag, interfaces[j]);
            }

            // try super class
            this.addInterfaces(service, serviceTag, description.getSuperClass());
        }
    }

    protected void testReference(Map references, JavaTag reference, String defaultName, boolean isInspectedClass)
    throws MojoExecutionException {
        final String refName = this.getReferenceName(reference, defaultName);

        if ( refName != null ) {
            if ( references.containsKey(refName) ) {
                // if the current class is the class we are currently inspecting, we
                // have found a duplicate definition
                if ( isInspectedClass ) {
                    throw new MojoExecutionException("Duplicate definition for reference " + refName + " in class " + reference.getJavaClassDescription().getName());
                }
            } else {
                references.put(refName, reference);
            }
        }
    }

    protected String getReferenceName(JavaTag reference, String defaultName) {
        String name = reference.getNamedParameter(Constants.REFERENCE_NAME);
        if (StringUtils.isEmpty(name)) {
            name = defaultName;
        }

        if (!StringUtils.isEmpty(name)) {
            return name;
        }
        return null;
    }

    /**
     * @param reference
     * @param defaultName
     * @param component
     */
    protected void doReference(JavaTag reference, String name, Component component)
    throws MojoExecutionException {
        // ensure interface
        String type = reference.getNamedParameter(Constants.REFERENCE_INTERFACE);
        if (StringUtils.isEmpty(type)) {
            if ( reference.getField() != null ) {
                type = reference.getField().getType();
            }
        }

        final Reference ref = new Reference(reference, component.getJavaClassDescription());
        ref.setName(name);
        ref.setInterfacename(type);
        ref.setCardinality(reference.getNamedParameter(Constants.REFERENCE_CARDINALITY));
        if ( ref.getCardinality() == null ) {
            ref.setCardinality("1..1");
        }
        ref.setPolicy(reference.getNamedParameter(Constants.REFERENCE_POLICY));
        if ( ref.getPolicy() == null ) {
            ref.setPolicy("static");
        }
        ref.setTarget(reference.getNamedParameter(Constants.REFERENCE_TARGET));
        final String bindValue = reference.getNamedParameter(Constants.REFERENCE_BIND);
        if ( bindValue != null ) {
            ref.setBind(bindValue);
        }
        final String unbindValue = reference.getNamedParameter(Constants.REFERENCE_UNDBIND);
        if ( unbindValue != null ) {
            ref.setUnbind(unbindValue);
        }
        // if this is a field with a single cardinality,
        // we look for the bind/unbind methods
        // and create them if they are not availabe
        if ( this.generateAccessors ) {
            if ( reference.getField() != null && component.getJavaClassDescription() instanceof ModifiableJavaClassDescription ) {
                if ( ref.getCardinality().equals("0..1") || ref.getCardinality().equals("1..1") ) {
                    boolean createBind = false;
                    boolean createUnbind = false;
                    // Only create method if no bind name has been specified
                    if ( bindValue == null && ref.findMethod(ref.getBind()) == null ) {
                        // create bind method
                        createBind = true;
                    }
                    if ( unbindValue == null && ref.findMethod(ref.getUnbind()) == null ) {
                        // create unbind method
                        createUnbind = true;
                    }
                    if ( createBind || createUnbind ) {
                        ((ModifiableJavaClassDescription)component.getJavaClassDescription()).addMethods(name, type, createBind, createUnbind);
                    }
                }
            }
        }
        component.addReference(ref);
    }

    public static boolean getBoolean(JavaTag tag, String name, boolean defaultValue) {
        String value = tag.getNamedParameter(name);
        return (value == null) ? defaultValue : Boolean.valueOf(value).booleanValue();
    }
}
