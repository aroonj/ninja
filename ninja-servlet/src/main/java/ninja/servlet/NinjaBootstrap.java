/**
 * Copyright (C) 2012-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ninja.servlet;

import com.google.common.base.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import ninja.Configuration;
import ninja.Context;
import ninja.Ninja;
import ninja.Route;
import ninja.Router;
import ninja.bodyparser.BodyParserEngine;
import ninja.bodyparser.BodyParserEngineManager;
import ninja.template.TemplateEngine;
import ninja.template.TemplateEngineManager;
import ninja.application.ApplicationRoutes;
import ninja.lifecycle.LifecycleSupport;
import ninja.logging.LogbackConfigurator;
import ninja.scheduler.SchedulerSupport;
import ninja.utils.NinjaConstant;
import ninja.utils.NinjaPropertiesImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Singleton;
import com.google.inject.Stage;
import com.google.inject.servlet.ServletModule;
import ninja.NinjaDefault;


public class NinjaBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(NinjaBootstrap.class);

    private static final String APPLICATION_GUICE_MODULE_CONVENTION_LOCATION = "conf.Module";
    private static final String APPLICATION_GUICE_SERVLET_MODULE_CONVENTION_LOCATION = "conf.ServletModule";
    private static final String ROUTES_CONVENTION_LOCATION = "conf.Routes";
    private static final String NINJA_CONVENTION_LOCATION = "conf.Ninja";

    private final NinjaPropertiesImpl ninjaProperties;

    private Injector injector = null;

    public NinjaBootstrap(NinjaPropertiesImpl ninjaProperties) {

        Preconditions.checkNotNull(ninjaProperties);

        this.ninjaProperties = ninjaProperties;
    }

    public Injector getInjector() {
        return injector;
    }

    public synchronized void boot() {

        initLogbackIfLogbackIsOnTheClassPathOtherwiseDoNotInitLogging();

        if (injector != null) {
            throw new RuntimeException("NinjaBootstap already booted");
        }

        long startTime = System.currentTimeMillis();

        injector = initInjector();

        long injectorStartupTime = System.currentTimeMillis() - startTime;
        logger.info("Ninja injector started in " + injectorStartupTime + " ms.");

        Preconditions.checkNotNull(injector, "Ninja injector cannot be generated. Please check log for further errors.");

        Ninja ninja = injector.getInstance(Ninja.class);
        ninja.onFrameworkStart();
    }

    public synchronized void shutdown() {
        if (injector != null) {
            Ninja ninja = injector.getInstance(Ninja.class);
            ninja.onFrameworkShutdown();
            injector = null;
            ninja = null;
        } else {
            logger.error("Shutdown of Ninja not clean => injector already null.");
        }
    }

    private Injector initInjector() {

        try {
            
            List<Module> modulesToLoad = new ArrayList<>();

            // Bind lifecycle support
            modulesToLoad.add(LifecycleSupport.getModule());
            // Scheduling support
            modulesToLoad.add(SchedulerSupport.getModule());

            // Get base configuration of Ninja:
            modulesToLoad.add(new Configuration(ninjaProperties));
            modulesToLoad.add(new AbstractModule() {
                @Override
                protected void configure() {
                    bind(Context.class).to(ContextImpl.class);
                }
            });

            // get custom base package for application modules and routes
            Optional<String> applicationModulesBasePackage 
                    = Optional.fromNullable(ninjaProperties.get(
                            NinjaConstant.APPLICATION_MODULES_BASE_PACKAGE));
            
            // Load main application module:
            String applicationConfigurationClassName = getClassNameWithOptionalUserDefinedPrefix(
                    applicationModulesBasePackage, 
                    APPLICATION_GUICE_MODULE_CONVENTION_LOCATION);

            if (doesClassExist(applicationConfigurationClassName)) {
                
                Class<?> applicationConfigurationClass = Class
                        .forName(applicationConfigurationClassName);

                AbstractModule applicationConfiguration = (AbstractModule) applicationConfigurationClass
                        .getConstructor().newInstance();

                modulesToLoad.add(applicationConfiguration);
                
            }

            // Load servlet module. By convention this is a ServletModule where 
            // the user can register other servlets and servlet filters
            // If the file does not exist we simply load the default servlet
            String servletModuleClassName = 
                getClassNameWithOptionalUserDefinedPrefix(
                    applicationModulesBasePackage,
                    APPLICATION_GUICE_SERVLET_MODULE_CONVENTION_LOCATION);
            
            if (doesClassExist(servletModuleClassName)) {
                
                Class<?> servletModuleClass = Class
                        .forName(servletModuleClassName);

                ServletModule servletModule = (ServletModule) servletModuleClass
                        .getConstructor().newInstance();

                modulesToLoad.add(servletModule);

            } else {
                // The servlet Module does not exist => we load the default one.                
                ServletModule servletModule = new ServletModule() {

                    @Override
                    protected void configureServlets() {
                        bind(NinjaServletDispatcher.class).asEagerSingleton();
                        serve("/*").with(NinjaServletDispatcher.class);
                    }

                };

                modulesToLoad.add(servletModule);

            }
            
            
            initializeUserSuppliedConfNinjaOrNinjaDefault(
                    applicationModulesBasePackage, 
                    modulesToLoad);


            // And let the injector generate all instances and stuff:
            injector = Guice.createInjector(Stage.PRODUCTION, modulesToLoad);

            initializeRouterWithRoutesOfUserApplication(applicationModulesBasePackage);

            TemplateEngineManager templateEngineManager = injector
                    .getInstance(TemplateEngineManager.class);
            logTemplateEngines(templateEngineManager);

            BodyParserEngineManager bodyParserEngineManager = injector
                    .getInstance(BodyParserEngineManager.class);
            logBodyParserEngines(bodyParserEngineManager);

            return injector;

        } catch (Exception exception) {
            logger.error("Fatal error booting Ninja", exception);
        }
        return null;
    }

    private boolean doesClassExist(String nameWithPackage) {

        boolean exists = false;

        try {
            Class.forName(nameWithPackage, false, this.getClass()
                    .getClassLoader());
            exists = true;
        } catch (ClassNotFoundException e) {
            exists = false;
        }

        return exists;

    }

    private String getClassNameWithOptionalUserDefinedPrefix(
            Optional<String> optionalUserDefinedPrefixForPackage, 
            String classLocationAsDefinedByNinja) {
        
        if (optionalUserDefinedPrefixForPackage.isPresent()) {
            return new StringBuilder(
                    optionalUserDefinedPrefixForPackage.get())
                    .append('.')
                    .append(classLocationAsDefinedByNinja)
                    .toString();
        } else {
            return classLocationAsDefinedByNinja;
        }
    }

    private void initLogbackIfLogbackIsOnTheClassPathOtherwiseDoNotInitLogging() {
        // init logging at the very very top
        try {
            Class.forName("ch.qos.logback.classic.joran.JoranConfigurator");
            LogbackConfigurator.initConfiguration(ninjaProperties);
            logger.info("Successfully configured Logback.");
             // It is available
        } catch (ClassNotFoundException exception) {
            logger.info(
                    "Logback is not on classpath (you are probably using slf4j-jdk14). I did not configure anything. It's up to you now...", exception);
        }

    }
    
    private final void initializeUserSuppliedConfNinjaOrNinjaDefault(
            Optional<String> applicationModulesBasePacakge,
            List<Module> modulesToLoad) throws Exception {
    
            String ninjaClassName = 
                    getClassNameWithOptionalUserDefinedPrefix(
                            applicationModulesBasePacakge,
                            NINJA_CONVENTION_LOCATION);
            
            final Class<? extends Ninja> ninjaClass;
                    
            if (doesClassExist(ninjaClassName)) {
                
                final Class<?> clazzPotentially = Class.forName(ninjaClassName);
                
                if (Ninja.class.isAssignableFrom(clazzPotentially)) {
                    
                    ninjaClass = (Class<? extends Ninja>) clazzPotentially;
                        
                } else {
                    
                    final String ERROR_MESSAGE = String.format(
                            "Found a class %s in your application's conf directory."
                            + " This class does not implement Ninja interface %s. "
                            + " Please implement the interface or remove the class.",
                            ninjaClassName, 
                            Ninja.class.getName());
                    
                    logger.error(ERROR_MESSAGE);  
                    
                    throw new IllegalStateException(ERROR_MESSAGE);

                }

            } else {
                
               ninjaClass = NinjaDefault.class;
                
            }
            
            modulesToLoad.add(new AbstractModule() {
                @Override
                protected void configure() {
                    bind(Ninja.class).to(ninjaClass).in(Singleton.class);
                }
            });

    }
    
    
    public void initializeRouterWithRoutesOfUserApplication(
            Optional<String> applicationModulesBasePackage) 
            throws Exception {
        // Init routes
        String routesClassName = 
                getClassNameWithOptionalUserDefinedPrefix(
                        applicationModulesBasePackage,
                        ROUTES_CONVENTION_LOCATION);

        if (doesClassExist(routesClassName)) {

            Class<?> clazz = Class.forName(routesClassName);
            ApplicationRoutes applicationRoutes = (ApplicationRoutes) injector
                    .getInstance(clazz);

            Router router = injector.getInstance(Router.class);

            applicationRoutes.init(router);
            router.compileRoutes();

            logRoutes(router);

        }
    
    }

    protected void logRoutes(Router router) {
        // determine the width of the columns
        int maxMethodLen = 0;
        int maxPathLen = 0;
        int maxControllerLen = 0;

        for (Route route : router.getRoutes()) {

            maxMethodLen = Math.max(maxMethodLen, route.getHttpMethod().length());
            maxPathLen = Math.max(maxPathLen, route.getUri().length());

            if (route.getControllerClass() != null) {

                int controllerLen = route.getControllerClass().getName().length()
                    + route.getControllerMethod().getName().length();
                maxControllerLen = Math.max(maxControllerLen, controllerLen);

            }

        }

        // log the routing table
        int borderLen = 10 + maxMethodLen + maxPathLen + maxControllerLen;
        String border = Strings.padEnd("", borderLen, '-');

        logger.info(border);
        logger.info("Registered routes");
        logger.info(border);

        for (Route route : router.getRoutes()) {

            if (route.getControllerClass() != null) {

                logger.info("{} {}  =>  {}.{}()",
                    Strings.padEnd(route.getHttpMethod(), maxMethodLen, ' '),
                    Strings.padEnd(route.getUri(), maxPathLen, ' '),
                    route.getControllerClass().getName(),
                    route.getControllerMethod().getName());

            } else {

              logger.info("{} {}", route.getHttpMethod(), route.getUri());

            }

        }

        logger.info(border);

    }

    protected void logTemplateEngines(TemplateEngineManager templateEngineManager) {
        Set<String> outputTypes = templateEngineManager.getContentTypes();
        if (outputTypes.isEmpty()) {

            logger.error("No registered template engines?! Please install a template module!");
            return;

        }

        int maxContentTypeLen = 0;
        int maxTemplateEngineLen = 0;

        for (String contentType : outputTypes) {

            TemplateEngine templateEngine = templateEngineManager
                    .getTemplateEngineForContentType(contentType);

            maxContentTypeLen = Math.max(maxContentTypeLen,
                    contentType.length());
            maxTemplateEngineLen = Math.max(maxTemplateEngineLen,
                    templateEngine.getClass().getName().length());

        }

        int borderLen = 10 + maxContentTypeLen + maxTemplateEngineLen;
        String border = Strings.padEnd("", borderLen, '-');

        logger.info("Registered response template engines");
        logger.info(border);

        for (String contentType : outputTypes) {

            TemplateEngine templateEngine = templateEngineManager
                    .getTemplateEngineForContentType(contentType);
            logger.info("{}  =>  {}",
                    Strings.padEnd(contentType, maxContentTypeLen, ' '),
                    templateEngine.getClass().getName());

        }

        logger.info(border);

    }

    protected void logBodyParserEngines(BodyParserEngineManager bodyParserEngineManager) {
        Set<String> outputTypes = bodyParserEngineManager.getContentTypes();

        int maxContentTypeLen = 0;
        int maxBodyParserEngineLen = 0;

        for (String contentType : outputTypes) {

            BodyParserEngine bodyParserEngine = bodyParserEngineManager
                    .getBodyParserEngineForContentType(contentType);

            maxContentTypeLen = Math.max(maxContentTypeLen,
                    contentType.length());
            maxBodyParserEngineLen = Math.max(maxBodyParserEngineLen,
                    bodyParserEngine.getClass().getName().length());

        }

        int borderLen = 10 + maxContentTypeLen + maxBodyParserEngineLen;
        String border = Strings.padEnd("", borderLen, '-');

        logger.info("Registered request bodyparser engines");
        logger.info(border);

        for (String contentType : outputTypes) {

            BodyParserEngine templateEngine = bodyParserEngineManager
                    .getBodyParserEngineForContentType(contentType);
            logger.info("{}  =>  {}",
                    Strings.padEnd(contentType, maxContentTypeLen, ' '),
                    templateEngine.getClass().getName());

        }

        logger.info(border);

    }
}
