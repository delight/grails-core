/*
 * Copyright 2004-2005 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.groovy.grails.plugins;

import grails.util.Environment;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovySystem;
import groovy.lang.MetaClassRegistry;
import groovy.xml.DOMBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletContext;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.grails.commons.GrailsApplication;
import org.codehaus.groovy.grails.commons.spring.WebRuntimeSpringConfiguration;
import org.codehaus.groovy.grails.io.support.GrailsResourceUtils;
import org.codehaus.groovy.grails.plugins.exceptions.PluginException;
import org.codehaus.groovy.grails.support.ParentApplicationContextAware;
import org.codehaus.groovy.runtime.IOGroovyMethods;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/**
 * <p>Handles the loading and management of plug-ins in the Grails system.
 * A plugin is just like a normal Grails application except that it contains a file ending
 * in *Plugin.groovy in the root of the directory.
 * <p/>
 * <p>A Plugin class is a Groovy class that has a version and optionally closures
 * called doWithSpring, doWithContext and doWithWebDescriptor
 * <p/>
 * <p>The doWithSpring closure uses the BeanBuilder syntax (@see grails.spring.BeanBuilder) to
 * provide runtime configuration of Grails via Spring
 * <p/>
 * <p>The doWithContext closure is called after the Spring ApplicationContext is built and accepts
 * a single argument (the ApplicationContext)
 * <p/>
 * <p>The doWithWebDescriptor uses mark-up building to provide additional functionality to the web.xml
 * file
 * <p/>
 * <p> Example:
 * <pre>
 * class ClassEditorGrailsPlugin {
 *      def version = '1.1'
 *      def doWithSpring = { application ->
 *          classEditor(org.springframework.beans.propertyeditors.ClassEditor, application.classLoader)
 *      }
 * }
 * </pre>
 * <p/>
 * <p>A plugin can also define "dependsOn" and "evict" properties that specify what plugins the plugin
 * depends on and which ones it is incompatible with and should evict
 *
 * @author Graeme Rocher
 * @since 0.4
 */
public class DefaultGrailsPluginManager extends AbstractGrailsPluginManager {

    private static final Log LOG = LogFactory.getLog(DefaultGrailsPluginManager.class);
    protected static final Class<?>[] COMMON_CLASSES = {
        Boolean.class, Byte.class, Character.class, Class.class, Double.class, Float.class,
        Integer.class, Long.class, Number.class, Short.class, String.class, BigInteger.class,
        BigDecimal.class, URL.class, URI.class };

    private List<GrailsPlugin> delayedLoadPlugins = new LinkedList<GrailsPlugin>();
    private ApplicationContext parentCtx;
    private PathMatchingResourcePatternResolver resolver;
    private Map<GrailsPlugin, String[]> delayedEvictions = new HashMap<GrailsPlugin, String[]>();
    private ServletContext servletContext;
    private Map<String, Set<GrailsPlugin>> pluginToObserverMap = new HashMap<String, Set<GrailsPlugin>>();

    private PluginFilter pluginFilter;
    private static final String GRAILS_PLUGIN_SUFFIX = "GrailsPlugin";
    private List<GrailsPlugin> userPlugins = new ArrayList<GrailsPlugin>();

    public DefaultGrailsPluginManager(String resourcePath, GrailsApplication application) {
        super(application);
        Assert.notNull(application, "Argument [application] cannot be null!");

        resolver = new PathMatchingResourcePatternResolver();
        try {
            pluginResources = resolver.getResources(resourcePath);
        }
        catch (IOException ioe) {
            LOG.debug("Unable to load plugins for resource path " + resourcePath, ioe);
        }
        //corePlugins = new PathMatchingResourcePatternResolver().getResources("classpath:org/codehaus/groovy/grails/**/plugins/**GrailsPlugin.groovy");
        this.application = application;
        setPluginFilter();
    }

    public DefaultGrailsPluginManager(String[] pluginResources, GrailsApplication application) {
        super(application);
        resolver = new PathMatchingResourcePatternResolver();

        List<Resource> resourceList = new ArrayList<Resource>();
        for (String resourcePath : pluginResources) {
            try {
                resourceList.addAll(Arrays.asList(resolver.getResources(resourcePath)));
            }
            catch (IOException ioe) {
                LOG.debug("Unable to load plugins for resource path " + resourcePath, ioe);
            }
        }

        this.pluginResources = resourceList.toArray(new Resource[resourceList.size()]);
        this.application = application;
        setPluginFilter();
    }

    public DefaultGrailsPluginManager(Class<?>[] plugins, GrailsApplication application) {
        super(application);
        pluginClasses = plugins;
        resolver = new PathMatchingResourcePatternResolver();
        //this.corePlugins = new PathMatchingResourcePatternResolver().getResources("classpath:org/codehaus/groovy/grails/**/plugins/**GrailsPlugin.groovy");
        this.application = application;
        setPluginFilter();
    }

    public DefaultGrailsPluginManager(Resource[] pluginFiles, GrailsApplication application) {
        super(application);
        resolver = new PathMatchingResourcePatternResolver();
        pluginResources = pluginFiles;
        this.application = application;
        setPluginFilter();
    }

    public GrailsPlugin[] getUserPlugins() {
        return userPlugins.toArray(new GrailsPlugin[userPlugins.size()]);
    }

    private void setPluginFilter() {
        pluginFilter = new PluginFilterRetriever().getPluginFilter(application.getConfig());
    }

    /**
     * @deprecated Will be removed in a future version of Grails
     */
    @Deprecated
    public void startPluginChangeScanner() {
        // do nothing
    }

    /**
     * @deprecated Will be removed in a future version of Grails
     */
    @Deprecated
    public void stopPluginChangeScanner() {
        // do nothing
    }

    public void refreshPlugin(String name) {
        if (hasGrailsPlugin(name)) {
            getGrailsPlugin(name).refresh();
        }
    }

    public Collection<GrailsPlugin> getPluginObservers(GrailsPlugin plugin) {
        Assert.notNull(plugin, "Argument [plugin] cannot be null");

        Collection<GrailsPlugin> c = pluginToObserverMap.get(plugin.getName());

        // Add any wildcard observers.
        Collection<GrailsPlugin> wildcardObservers = pluginToObserverMap.get("*");
        if (wildcardObservers != null) {
            if (c != null) {
                c.addAll(wildcardObservers);
            }
            else {
                c = wildcardObservers;
            }
        }

        if (c != null) {
            // Make sure this plugin is not observing itself!
            c.remove(plugin);
            return c;
        }

        return Collections.emptySet();
    }

    @SuppressWarnings("rawtypes")
    public void informObservers(String pluginName, Map event) {
        GrailsPlugin plugin = getGrailsPlugin(pluginName);
        if (plugin == null) {
            return;
        }

        for (GrailsPlugin observingPlugin : getPluginObservers(plugin)) {
            observingPlugin.notifyOfEvent(event);
        }
    }

    /* (non-Javadoc)
     * @see org.codehaus.groovy.grails.plugins.GrailsPluginManager#loadPlugins()
     */
    public void loadPlugins() throws PluginException {
        if (initialised) {
            return;
        }

        ClassLoader gcl = application.getClassLoader();

        attemptLoadPlugins(gcl);

        if (!delayedLoadPlugins.isEmpty()) {
            loadDelayedPlugins();
        }
        if (!delayedEvictions.isEmpty()) {
            processDelayedEvictions();
        }

        pluginList = sortPlugins(pluginList);
        initializePlugins();
        initialised = true;
    }

    private List<GrailsPlugin> sortPlugins(List<GrailsPlugin> toSort) {

        List<GrailsPlugin> newList = new ArrayList<GrailsPlugin>(toSort);

        for (GrailsPlugin plugin : toSort) {
            int i = newList.indexOf(plugin);
            for (String loadBefore : plugin.getLoadBeforeNames()) {
                final GrailsPlugin loadBeforePlugin = getGrailsPlugin(loadBefore);
                int j = newList.indexOf(loadBeforePlugin);
                if (j > -1 && i > j) {
                    newList.remove(plugin);
                    newList.add(j, plugin);
                }
            }

            i = newList.indexOf(plugin);
            for (String loadAfter : plugin.getLoadAfterNames()) {
                final GrailsPlugin loadAfterPlugin = getGrailsPlugin(loadAfter);
                int j = newList.indexOf(loadAfterPlugin);
                if (j > -1 && i < j) {
                    newList.remove(plugin);
                    newList.add(j, plugin);
                }
            }
        }
        return newList;
    }

    private void attemptLoadPlugins(ClassLoader gcl) {
        // retrieve load core plugins first

        List<GrailsPlugin> grailsCorePlugins = loadCorePlugins ? findCorePlugins() : new ArrayList<GrailsPlugin>();

        List<GrailsPlugin> grailsUserPlugins = findUserPlugins(gcl);
        userPlugins = grailsUserPlugins;

        List<GrailsPlugin> allPlugins = new ArrayList<GrailsPlugin> (grailsCorePlugins);
        allPlugins.addAll(grailsUserPlugins);

        //filtering applies to user as well as core plugins
        List<GrailsPlugin> filteredPlugins = getPluginFilter().filterPluginList(allPlugins);

        //make sure core plugins are loaded first
        List<GrailsPlugin> orderedCorePlugins = new ArrayList<GrailsPlugin> ();
        List<GrailsPlugin> orderedUserPlugins = new ArrayList<GrailsPlugin> ();

        for (GrailsPlugin plugin : filteredPlugins) {
            if (grailsCorePlugins != null) {
                if (grailsCorePlugins.contains(plugin)) {
                    orderedCorePlugins.add(plugin);
                }
                else {
                    orderedUserPlugins.add(plugin);
                }
            }
        }

        List<GrailsPlugin> orderedPlugins = new ArrayList<GrailsPlugin> ();
        orderedPlugins.addAll(orderedCorePlugins);
        orderedPlugins.addAll(orderedUserPlugins);

        for (GrailsPlugin plugin : orderedPlugins) {
            attemptPluginLoad(plugin);
        }
    }

    private List<GrailsPlugin> findCorePlugins() {
        CorePluginFinder finder = new CorePluginFinder(application);
        finder.setParentApplicationContext(parentCtx);

        List<GrailsPlugin> grailsCorePlugins = new ArrayList<GrailsPlugin>();

        final Class<?>[] corePluginClasses = finder.getPluginClasses();

        for (Class<?> pluginClass : corePluginClasses) {
            if (pluginClass != null && !Modifier.isAbstract(pluginClass.getModifiers()) && pluginClass != DefaultGrailsPlugin.class) {
                final BinaryGrailsPluginDescriptor binaryDescriptor = finder.getBinaryDescriptor(pluginClass);
                GrailsPlugin plugin;
                if (binaryDescriptor != null) {
                    plugin = createBinaryGrailsPlugin(pluginClass, binaryDescriptor);
                }
                else {
                    plugin = createGrailsPlugin(pluginClass);
                }
                plugin.setApplicationContext(applicationContext);
                grailsCorePlugins.add(plugin);
            }
        }
        return grailsCorePlugins;
    }

    private GrailsPlugin createBinaryGrailsPlugin(Class<?> pluginClass, BinaryGrailsPluginDescriptor binaryDescriptor) {
        return new BinaryGrailsPlugin(pluginClass, binaryDescriptor, application);
    }

    protected GrailsPlugin createGrailsPlugin(Class<?> pluginClass) {
        return new DefaultGrailsPlugin(pluginClass, application);
    }

    protected GrailsPlugin createGrailsPlugin(Class<?> pluginClass, Resource resource) {
        return new DefaultGrailsPlugin(pluginClass, resource, application);
    }

    private List<GrailsPlugin> findUserPlugins(ClassLoader gcl) {
        List<GrailsPlugin> grailsUserPlugins = new ArrayList<GrailsPlugin>();

        LOG.info("Attempting to load [" + pluginResources.length + "] user defined plugins");
        for (Resource r : pluginResources) {
            Class<?> pluginClass = loadPluginClass(gcl, r);

            if (isGrailsPlugin(pluginClass)) {
                GrailsPlugin plugin = createGrailsPlugin(pluginClass, r);
                //attemptPluginLoad(plugin);
                grailsUserPlugins.add(plugin);
            } else {
                LOG.warn("Class [" + pluginClass + "] not loaded as plug-in. Grails plug-ins must end with the convention 'GrailsPlugin'!");
            }
        }

        for (Class<?> pluginClass : pluginClasses) {
            if (isGrailsPlugin(pluginClass)) {
                GrailsPlugin plugin = createGrailsPlugin(pluginClass);
                //attemptPluginLoad(plugin);
                grailsUserPlugins.add(plugin);
            } else {
                LOG.warn("Class [" + pluginClass + "] not loaded as plug-in. Grails plug-ins must end with the convention 'GrailsPlugin'!");
            }
        }
        return grailsUserPlugins;
    }


    private boolean isGrailsPlugin(Class<?> pluginClass) {
        return pluginClass != null && pluginClass.getName().endsWith(GRAILS_PLUGIN_SUFFIX);
    }

    private void processDelayedEvictions() {
        for (Map.Entry<GrailsPlugin, String[]> entry : delayedEvictions.entrySet()) {
            GrailsPlugin plugin = entry.getKey();
            for (String pluginName : entry.getValue()) {
                evictPlugin(plugin, pluginName);
            }
        }
    }

    private void initializePlugins() {
        for (Object plugin : plugins.values()) {
            if (plugin instanceof ApplicationContextAware) {
                ((ApplicationContextAware) plugin).setApplicationContext(applicationContext);
            }
        }
    }

    /**
     * This method will attempt to load that plug-ins not loaded in the first pass
     */
    private void loadDelayedPlugins() {
        while (!delayedLoadPlugins.isEmpty()) {
            GrailsPlugin plugin = delayedLoadPlugins.remove(0);
            if (areDependenciesResolved(plugin)) {
                if (!hasValidPluginsToLoadBefore(plugin)) {
                    registerPlugin(plugin);
                }
                else {
                    delayedLoadPlugins.add(plugin);
                }
            }
            else {
                // ok, it still hasn't resolved the dependency after the initial
                // load of all plugins. All hope is not lost, however, so lets first
                // look inside the remaining delayed loads before giving up
                boolean foundInDelayed = false;
                for (GrailsPlugin remainingPlugin : delayedLoadPlugins) {
                    if (isDependentOn(plugin, remainingPlugin)) {
                        foundInDelayed = true;
                        break;
                    }
                }
                if (foundInDelayed) {
                    delayedLoadPlugins.add(plugin);
                }
                else {
                    failedPlugins.put(plugin.getName(), plugin);
                    LOG.warn("WARNING: Plugin [" + plugin.getName() + "] cannot be loaded because its dependencies [" +
                            ArrayUtils.toString(plugin.getDependencyNames()) + "] cannot be resolved");
                }
            }
        }
    }

    private boolean hasValidPluginsToLoadBefore(GrailsPlugin plugin) {
        String[] loadAfterNames = plugin.getLoadAfterNames();
        for (Object delayedLoadPlugin : delayedLoadPlugins) {
            GrailsPlugin other = (GrailsPlugin) delayedLoadPlugin;
            for (String name : loadAfterNames) {
                if (other.getName().equals(name)) {
                    return hasDelayedDependencies(other) || areDependenciesResolved(other);
                }
            }
        }
        return false;
    }

    private boolean hasDelayedDependencies(GrailsPlugin other) {
        String[] dependencyNames = other.getDependencyNames();
        for (String dependencyName : dependencyNames) {
            for (GrailsPlugin grailsPlugin : delayedLoadPlugins) {
                if (grailsPlugin.getName().equals(dependencyName)) return true;
            }
        }
        return false;
    }

    /**
     * Checks whether the first plugin is dependant on the second plugin.
     *
     * @param plugin     The plugin to check
     * @param dependency The plugin which the first argument may be dependant on
     * @return true if it is
     */
    private boolean isDependentOn(GrailsPlugin plugin, GrailsPlugin dependency) {
        for (String name : plugin.getDependencyNames()) {
            String requiredVersion = plugin.getDependentVersion(name);

            if (name.equals(dependency.getName()) &&
                    GrailsPluginUtils.isValidVersion(dependency.getVersion(), requiredVersion))
                return true;
        }
        return false;
    }

    private boolean areDependenciesResolved(GrailsPlugin plugin) {
        for (String name : plugin.getDependencyNames()) {
            if (!hasGrailsPlugin(name, plugin.getDependentVersion(name))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns true if there are no plugins left that should, if possible, be loaded before this plugin.
     *
     * @param plugin The plugin
     * @return true if there are
     */
    private boolean areNoneToLoadBefore(GrailsPlugin plugin) {
        for (String name : plugin.getLoadAfterNames()) {
            if (getGrailsPlugin(name) == null) {
                return false;
            }
        }
        return true;
    }

    private Class<?> loadPluginClass(ClassLoader cl, Resource r) {
        Class<?> pluginClass;
        if (cl instanceof GroovyClassLoader) {
            try {
                if (LOG.isInfoEnabled()) {
                    LOG.info("Parsing & compiling " + r.getFilename());
                }
                pluginClass = ((GroovyClassLoader)cl).parseClass(IOGroovyMethods.getText(r.getInputStream()));
            }
            catch (CompilationFailedException e) {
                throw new PluginException("Error compiling plugin [" + r.getFilename() + "] " + e.getMessage(), e);
            }
            catch (IOException e) {
                throw new PluginException("Error reading plugin [" + r.getFilename() + "] " + e.getMessage(), e);
            }
        }
        else {
            String className = null;
            try {
                className = GrailsResourceUtils.getClassName(r.getFile().getAbsolutePath());
            } catch (IOException e) {
                throw new PluginException("Cannot find plugin class [" + className + "] resource: [" + r.getFilename()+"]", e);
            }
            try {
                pluginClass = Class.forName(className, true, cl);
            }
            catch (ClassNotFoundException e) {
                throw new PluginException("Cannot find plugin class [" + className + "] resource: [" + r.getFilename()+"]", e);
            }
        }
        return pluginClass;
    }

    /**
     * Attempts to load a plugin based on its dependencies. If a plugin's dependencies cannot be resolved
     * it will add it to the list of dependencies to be resolved later.
     *
     * @param plugin The plugin
     */
    private void attemptPluginLoad(GrailsPlugin plugin) {
        if (areDependenciesResolved(plugin) && areNoneToLoadBefore(plugin)) {
            registerPlugin(plugin);
        }
        else {
            delayedLoadPlugins.add(plugin);
        }
    }

    private void registerPlugin(GrailsPlugin plugin) {
        if (!canRegisterPlugin(plugin)) {
            if (LOG.isInfoEnabled()) {
                LOG.info("Grails plugin " + plugin + " is disabled and was not loaded");
            }
            return;
        }

        if (LOG.isInfoEnabled()) {
            LOG.info("Grails plug-in [" + plugin.getName() + "] with version [" + plugin.getVersion() + "] loaded successfully");
        }

        if (plugin instanceof ParentApplicationContextAware) {
            ((ParentApplicationContextAware) plugin).setParentApplicationContext(parentCtx);
        }
        plugin.setManager(this);
        String[] evictionNames = plugin.getEvictionNames();
        if (evictionNames.length > 0) {
            delayedEvictions.put(plugin, evictionNames);
        }

        String[] observedPlugins = plugin.getObservedPluginNames();
        for (String observedPlugin : observedPlugins) {
            Set<GrailsPlugin> observers = pluginToObserverMap.get(observedPlugin);
            if (observers == null) {
                observers = new HashSet<GrailsPlugin>();
                pluginToObserverMap.put(observedPlugin, observers);
            }
            observers.add(plugin);
        }
        pluginList.add(plugin);
        plugins.put(plugin.getName(), plugin);
        classNameToPluginMap.put(plugin.getPluginClass().getName(), plugin);
    }

    protected boolean canRegisterPlugin(GrailsPlugin plugin) {
        Environment environment = Environment.getCurrent();
        return plugin.isEnabled() && plugin.supportsEnvironment(environment);
    }

    protected void evictPlugin(GrailsPlugin evictor, String evicteeName) {
        GrailsPlugin pluginToEvict = plugins.get(evicteeName);
        if (pluginToEvict != null) {
            pluginList.remove(pluginToEvict);
            plugins.remove(pluginToEvict.getName());

            if (LOG.isInfoEnabled()) {
                LOG.info("Grails plug-in " + pluginToEvict + " was evicted by " + evictor);
            }
        }
    }

    private boolean hasGrailsPlugin(String name, String version) {
        return getGrailsPlugin(name, version) != null;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
        for (GrailsPlugin plugin : pluginList) {
            plugin.setApplicationContext(applicationContext);
        }
    }

    public void setParentApplicationContext(ApplicationContext parent) {
        parentCtx = parent;
    }

    /**
     * @deprecated Replaced by agent-based reloading, will be removed in a future version of Grails
     */
    @Deprecated
    public void checkForChanges() {
        // do nothing
    }

    public void reloadPlugin(GrailsPlugin plugin) {
        plugin.doArtefactConfiguration();

        WebRuntimeSpringConfiguration springConfig = new WebRuntimeSpringConfiguration(parentCtx);
        springConfig.setServletContext(getServletContext());

        doRuntimeConfiguration(plugin.getName(), springConfig);
        springConfig.registerBeansWithContext((GenericApplicationContext)applicationContext);

        plugin.doWithApplicationContext(applicationContext);
        plugin.doWithDynamicMethods(applicationContext);
    }

    public void doWebDescriptor(Resource descriptor, Writer target) {
        try {
            doWebDescriptor(descriptor.getInputStream(), target);
        }
        catch (IOException e) {
            throw new PluginException("Unable to read web.xml [" + descriptor + "]: " + e.getMessage(), e);
        }
    }

    private void doWebDescriptor(InputStream inputStream, Writer target) {
        checkInitialised();
        try {
            Document document = DOMBuilder.parse(new InputStreamReader(inputStream));
            Element documentElement = document.getDocumentElement();

            for (GrailsPlugin plugin : pluginList) {
                if (plugin.supportsCurrentScopeAndEnvironment()) {
                    plugin.doWithWebDescriptor(documentElement);
                }
            }

            boolean areServlet3JarsPresent = ClassUtils.isPresent("javax.servlet.AsyncContext", Thread.currentThread().getContextClassLoader());
            String servletVersion = application.getMetadata().getServletVersion();
            if (areServlet3JarsPresent && GrailsVersionUtils.supportsAtLeastVersion(servletVersion, "3.0")) {
                new Servlet3AsyncWebXmlProcessor().process(documentElement);
            }
            writeWebDescriptorResult(documentElement, target);
        }
        catch (ParserConfigurationException e) {
            throw new PluginException("Unable to configure web.xml due to parser configuration problem: " + e.getMessage(), e);
        }
        catch (SAXException e) {
            throw new PluginException("XML parsing error configuring web.xml: " + e.getMessage(), e);
        }
        catch (IOException e) {
            throw new PluginException("Unable to read web.xml" + e.getMessage(), e);
        }
    }

    private void writeWebDescriptorResult(Element result, Writer output) throws IOException {
        try {
            TransformerFactory.newInstance().newTransformer().transform(
                    new DOMSource(result),
                    new StreamResult(output));
        } catch (TransformerException e) {
            throw new IOException("Error transforming web.xml: " + e.getMessage(), e);
        }
    }

    public void doWebDescriptor(File descriptor, Writer target) {
        try {
            doWebDescriptor(new FileInputStream(descriptor), target);
        }
        catch (FileNotFoundException e) {
            throw new PluginException("Unable to read web.xml [" + descriptor + "]: " + e.getMessage(), e);
        }
    }

    @Override
    public void setApplication(GrailsApplication application) {
        Assert.notNull(application, "Argument [application] cannot be null");
        this.application = application;
        for (GrailsPlugin plugin : pluginList) {
            plugin.setApplication(application);
        }
    }

    @Override
    public void doDynamicMethods() {
        checkInitialised();
        // remove common meta classes just to be sure
        MetaClassRegistry registry = GroovySystem.getMetaClassRegistry();
        for (Class<?> COMMON_CLASS : COMMON_CLASSES) {
            registry.removeMetaClass(COMMON_CLASS);
        }
        for (GrailsPlugin plugin : pluginList) {
            if (plugin.supportsCurrentScopeAndEnvironment()) {
                try {
                    plugin.doWithDynamicMethods(applicationContext);
                }
                catch (Throwable t) {
                    LOG.error("Error configuring dynamic methods for plugin " + plugin + ": " + t.getMessage(), t);
                }
            }
        }
    }

    public void setServletContext(ServletContext servletContext) {
        this.servletContext = servletContext;
    }

    public ServletContext getServletContext() {
        return servletContext;
    }

    void setPluginFilter(PluginFilter pluginFilter) {
        this.pluginFilter = pluginFilter;
    }

    private PluginFilter getPluginFilter() {
        if (pluginFilter == null) {
            pluginFilter = new IdentityPluginFilter();
        }
        return pluginFilter;
    }

    List<GrailsPlugin> getPluginList() {
        return Collections.unmodifiableList(pluginList);
    }
}
