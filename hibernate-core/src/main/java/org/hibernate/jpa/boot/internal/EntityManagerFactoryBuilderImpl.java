/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.jpa.boot.internal;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.sql.DataSource;

import org.hibernate.Internal;
import org.hibernate.ObjectNotFoundException;
import org.hibernate.SessionFactory;
import org.hibernate.SessionFactoryObserver;
import org.hibernate.boot.CacheRegionDefinition;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.SessionFactoryBuilder;
import org.hibernate.boot.archive.scan.internal.StandardScanOptions;
import org.hibernate.boot.beanvalidation.BeanValidationIntegrator;
import org.hibernate.boot.cfgxml.spi.CfgXmlAccessService;
import org.hibernate.boot.cfgxml.spi.LoadedConfig;
import org.hibernate.boot.cfgxml.spi.MappingReference;
import org.hibernate.boot.internal.ClassmateContext;
import org.hibernate.boot.jaxb.hbm.spi.JaxbHbmHibernateMapping;
import org.hibernate.boot.jaxb.hbm.spi.JaxbHbmRootEntityType;
import org.hibernate.boot.jaxb.spi.Binding;
import org.hibernate.boot.model.TypeContributor;
import org.hibernate.boot.model.convert.internal.ClassBasedConverterDescriptor;
import org.hibernate.boot.model.convert.spi.ConverterDescriptor;
import org.hibernate.boot.model.process.spi.ManagedResources;
import org.hibernate.boot.model.process.spi.MetadataBuildingProcess;
import org.hibernate.boot.registry.BootstrapServiceRegistry;
import org.hibernate.boot.registry.BootstrapServiceRegistryBuilder;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.registry.classloading.internal.TcclLookupPrecedence;
import org.hibernate.boot.registry.classloading.spi.ClassLoaderService;
import org.hibernate.boot.registry.selector.StrategyRegistrationProvider;
import org.hibernate.boot.registry.selector.spi.StrategySelector;
import org.hibernate.boot.spi.MetadataBuilderContributor;
import org.hibernate.boot.spi.MetadataBuilderImplementor;
import org.hibernate.boot.spi.MetadataImplementor;
import org.hibernate.boot.spi.SessionFactoryBuilderImplementor;
import org.hibernate.bytecode.enhance.spi.DefaultEnhancementContext;
import org.hibernate.bytecode.enhance.spi.EnhancementContext;
import org.hibernate.bytecode.enhance.spi.EnhancementException;
import org.hibernate.bytecode.enhance.spi.UnloadedClass;
import org.hibernate.bytecode.enhance.spi.UnloadedField;
import org.hibernate.bytecode.spi.ClassTransformer;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Environment;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.internal.CoreLogging;
import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.internal.util.NullnessHelper;
import org.hibernate.internal.util.PropertiesHelper;
import org.hibernate.internal.util.StringHelper;
import org.hibernate.internal.util.config.ConfigurationHelper;
import org.hibernate.jpa.boot.spi.EntityManagerFactoryBuilder;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.hibernate.jpa.boot.spi.JpaSettings;
import org.hibernate.jpa.boot.spi.PersistenceUnitDescriptor;
import org.hibernate.jpa.boot.spi.StrategyRegistrationProviderList;
import org.hibernate.jpa.boot.spi.TypeContributorList;
import org.hibernate.jpa.internal.util.LogHelper;
import org.hibernate.jpa.internal.util.PersistenceUnitTransactionTypeHelper;
import org.hibernate.proxy.EntityNotFoundDelegate;
import org.hibernate.resource.transaction.backend.jdbc.internal.JdbcResourceLocalTransactionCoordinatorBuilderImpl;
import org.hibernate.resource.transaction.backend.jta.internal.JtaTransactionCoordinatorBuilderImpl;
import org.hibernate.resource.transaction.spi.TransactionCoordinatorBuilder;
import org.hibernate.service.spi.ServiceBinding;
import org.hibernate.service.spi.ServiceRegistryImplementor;
import org.hibernate.service.spi.Stoppable;
import org.hibernate.tool.schema.spi.DelayedDropRegistryNotAvailableImpl;
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.spi.PersistenceUnitTransactionType;

import static java.lang.Boolean.parseBoolean;
import static java.util.Collections.unmodifiableMap;
import static org.hibernate.cfg.AvailableSettings.CFG_XML_FILE;
import static org.hibernate.cfg.AvailableSettings.CLASSLOADERS;
import static org.hibernate.cfg.AvailableSettings.CLASS_CACHE_PREFIX;
import static org.hibernate.cfg.AvailableSettings.COLLECTION_CACHE_PREFIX;
import static org.hibernate.cfg.AvailableSettings.DATASOURCE;
import static org.hibernate.cfg.AvailableSettings.DRIVER;
import static org.hibernate.cfg.AvailableSettings.JAKARTA_JDBC_DRIVER;
import static org.hibernate.cfg.AvailableSettings.JAKARTA_JDBC_PASSWORD;
import static org.hibernate.cfg.AvailableSettings.JAKARTA_JDBC_URL;
import static org.hibernate.cfg.AvailableSettings.JAKARTA_JDBC_USER;
import static org.hibernate.cfg.AvailableSettings.JAKARTA_JTA_DATASOURCE;
import static org.hibernate.cfg.AvailableSettings.JAKARTA_NON_JTA_DATASOURCE;
import static org.hibernate.cfg.AvailableSettings.JAKARTA_SHARED_CACHE_MODE;
import static org.hibernate.cfg.AvailableSettings.JAKARTA_TRANSACTION_TYPE;
import static org.hibernate.cfg.AvailableSettings.JAKARTA_VALIDATION_FACTORY;
import static org.hibernate.cfg.AvailableSettings.JAKARTA_VALIDATION_MODE;
import static org.hibernate.cfg.AvailableSettings.JPA_JDBC_DRIVER;
import static org.hibernate.cfg.AvailableSettings.JPA_JDBC_PASSWORD;
import static org.hibernate.cfg.AvailableSettings.JPA_JDBC_URL;
import static org.hibernate.cfg.AvailableSettings.JPA_JDBC_USER;
import static org.hibernate.cfg.AvailableSettings.JPA_JTA_DATASOURCE;
import static org.hibernate.cfg.AvailableSettings.JPA_NON_JTA_DATASOURCE;
import static org.hibernate.cfg.AvailableSettings.JPA_SHARED_CACHE_MODE;
import static org.hibernate.cfg.AvailableSettings.JPA_TRANSACTION_TYPE;
import static org.hibernate.cfg.AvailableSettings.JPA_VALIDATION_FACTORY;
import static org.hibernate.cfg.AvailableSettings.JPA_VALIDATION_MODE;
import static org.hibernate.cfg.AvailableSettings.PASS;
import static org.hibernate.cfg.AvailableSettings.PERSISTENCE_UNIT_NAME;
import static org.hibernate.cfg.AvailableSettings.SCANNER_DISCOVERY;
import static org.hibernate.cfg.AvailableSettings.SESSION_FACTORY_NAME;
import static org.hibernate.cfg.AvailableSettings.TRANSACTION_COORDINATOR_STRATEGY;
import static org.hibernate.cfg.AvailableSettings.URL;
import static org.hibernate.cfg.AvailableSettings.USER;
import static org.hibernate.cfg.BytecodeSettings.ENHANCER_ENABLE_ASSOCIATION_MANAGEMENT;
import static org.hibernate.cfg.BytecodeSettings.ENHANCER_ENABLE_DIRTY_TRACKING;
import static org.hibernate.cfg.BytecodeSettings.ENHANCER_ENABLE_LAZY_INITIALIZATION;
import static org.hibernate.cfg.TransactionSettings.FLUSH_BEFORE_COMPLETION;
import static org.hibernate.internal.log.DeprecationLogger.DEPRECATION_LOGGER;
import static org.hibernate.internal.util.StringHelper.isNotEmpty;

/**
 * @author Steve Ebersole
 */
@SuppressWarnings({"deprecation", "removal"})
public class EntityManagerFactoryBuilderImpl implements EntityManagerFactoryBuilder {
	private static final CoreMessageLogger log = CoreLogging.messageLogger( EntityManagerFactoryBuilderImpl.class );


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// New settings

	/**
	 * Names a {@link IntegratorProvider}
	 */
	public static final String INTEGRATOR_PROVIDER = JpaSettings.INTEGRATOR_PROVIDER;
	
	/**
	 * Names a {@link StrategyRegistrationProviderList}
	 */
	public static final String STRATEGY_REGISTRATION_PROVIDERS = JpaSettings.STRATEGY_REGISTRATION_PROVIDERS;
	
	/**
	 * Names a {@link TypeContributorList}
	 */
	public static final String TYPE_CONTRIBUTORS = JpaSettings.TYPE_CONTRIBUTORS;

	/**
	 * Names a {@link MetadataBuilderContributor}
	 */
	public static final String METADATA_BUILDER_CONTRIBUTOR = JpaSettings.METADATA_BUILDER_CONTRIBUTOR;


	private final PersistenceUnitDescriptor persistenceUnit;

	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// things built in first phase, needed for second phase
	private final Map<String,Object> configurationValues;
	private final StandardServiceRegistry standardServiceRegistry;
	private final ManagedResources managedResources;
	private final MetadataBuilderImplementor metamodelBuilder;

	private static class JpaEntityNotFoundDelegate implements EntityNotFoundDelegate, Serializable {
		/**
		 * Singleton access
		 */
		public static final JpaEntityNotFoundDelegate INSTANCE = new JpaEntityNotFoundDelegate();

		public void handleEntityNotFound(String entityName, Object identifier) {
			final ObjectNotFoundException exception = new ObjectNotFoundException( entityName, identifier );
			throw new EntityNotFoundException( exception.getMessage(), exception);
		}
	}

	public EntityManagerFactoryBuilderImpl(
			PersistenceUnitDescriptor persistenceUnit,
			Map<String, Object> integrationSettings) {
		this( persistenceUnit, integrationSettings, null, null, null );
	}

	public EntityManagerFactoryBuilderImpl(
			PersistenceUnitDescriptor persistenceUnit,
			Map<String, Object> integrationSettings,
			ClassLoader providedClassLoader ) {
		this( persistenceUnit, integrationSettings, providedClassLoader, null, null );
	}

	public EntityManagerFactoryBuilderImpl(
			PersistenceUnitDescriptor persistenceUnit,
			Map<String, Object> integrationSettings,
			ClassLoaderService providedClassLoaderService ) {
		this( persistenceUnit, integrationSettings, null, providedClassLoaderService, null );
	}

	/**
	 * For tests only
	 */
	@Internal
	public EntityManagerFactoryBuilderImpl(
			PersistenceUnitDescriptor persistenceUnitDescriptor,
			Map<String, Object> integration,
			Consumer<MergedSettings> mergedSettingsBaseline) {
		this( persistenceUnitDescriptor, integration, null, null, mergedSettingsBaseline );
	}

	private EntityManagerFactoryBuilderImpl(
			PersistenceUnitDescriptor persistenceUnit,
			Map<String,Object> integrationSettings,
			ClassLoader providedClassLoader,
			ClassLoaderService providedClassLoaderService,
			Consumer<MergedSettings> mergedSettingsBaseline) {

		LogHelper.logPersistenceUnitInformation( persistenceUnit );

		this.persistenceUnit = persistenceUnit;

		if ( integrationSettings == null ) {
			integrationSettings = new HashMap<>();
		}

		Map<Object,Object> mergedIntegrationSettings = null;
		final Properties properties = persistenceUnit.getProperties();
		if ( properties != null ) {
			// original integration setting entries take precedence
			mergedIntegrationSettings = new HashMap<>( properties );
			mergedIntegrationSettings.putAll( integrationSettings );
		}

		// Build the bootstrap service registry, which mainly handles classloader interactions
		final BootstrapServiceRegistry bsr = buildBootstrapServiceRegistry(
				mergedIntegrationSettings != null ? mergedIntegrationSettings : integrationSettings,
				providedClassLoader,
				providedClassLoaderService
		);

		try {
			// merge configuration sources and build the "standard" service registry
			final StandardServiceRegistryBuilder registryBuilder = getStandardServiceRegistryBuilder( bsr );

			final MergedSettings mergedSettings =
					mergeSettings( persistenceUnit, integrationSettings, registryBuilder, mergedSettingsBaseline );

			// flush before completion validation
			if ( "true".equals( mergedSettings.configurationValues.get( FLUSH_BEFORE_COMPLETION ) ) ) {
				log.definingFlushBeforeCompletionIgnoredInHem( FLUSH_BEFORE_COMPLETION );
				mergedSettings.configurationValues.put( FLUSH_BEFORE_COMPLETION, "false" );
			}

			// keep the merged config values for phase-2
			configurationValues = mergedSettings.getConfigurationValues();

			// Build the "standard" service registry
			registryBuilder.applySettings( configurationValues );

			standardServiceRegistry = registryBuilder.build();

			final MetadataSources metadataSources = new MetadataSources( standardServiceRegistry );
			metamodelBuilder =
					(MetadataBuilderImplementor)
							metadataSources.getMetadataBuilder( standardServiceRegistry );

			applyMetamodelBuilderSettings( mergedSettings, applyMappingResources( metadataSources ) );

			applyMetadataBuilderContributor();

			// todo : would be nice to have MetadataBuilder still do the handling of CfgXmlAccessService here
			//		another option is to immediately handle them here (probably in mergeSettings?) as we encounter them...
			final LoadedConfig aggregatedConfig =
					standardServiceRegistry.requireService( CfgXmlAccessService.class )
							.getAggregatedConfig();
			if ( aggregatedConfig != null ) {
				final List<MappingReference> mappingReferences = aggregatedConfig.getMappingReferences();
				if ( mappingReferences != null ) {
					for ( MappingReference mappingReference : mappingReferences ) {
						mappingReference.apply( metadataSources );
					}
				}
			}

			managedResources = MetadataBuildingProcess.prepare( metadataSources, metamodelBuilder.getBootstrapContext() );

			final Object validatorFactory = configurationValues.get( JAKARTA_VALIDATION_FACTORY );
			if ( validatorFactory == null ) {
				final Object legacyValidatorFactory = configurationValues.get( JPA_VALIDATION_FACTORY );
				if ( legacyValidatorFactory != null ) {
					DEPRECATION_LOGGER.deprecatedSetting( JPA_VALIDATION_FACTORY, JAKARTA_VALIDATION_FACTORY );
				}
				withValidatorFactory( legacyValidatorFactory );
			}
			else {
				withValidatorFactory( validatorFactory );
			}

			// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

			final boolean dirtyTrackingEnabled =
					readBooleanConfigurationValueDefaultTrue( ENHANCER_ENABLE_DIRTY_TRACKING );
			final boolean lazyInitializationEnabled =
					readBooleanConfigurationValueDefaultTrue( ENHANCER_ENABLE_LAZY_INITIALIZATION );
			final boolean associationManagementEnabled =
					readBooleanConfigurationValue( ENHANCER_ENABLE_ASSOCIATION_MANAGEMENT );

			if ( !lazyInitializationEnabled ) {
				DEPRECATION_LOGGER.deprecatedSettingForRemoval( ENHANCER_ENABLE_LAZY_INITIALIZATION, "true" );
			}
			if ( !dirtyTrackingEnabled ) {
				DEPRECATION_LOGGER.deprecatedSettingForRemoval( ENHANCER_ENABLE_DIRTY_TRACKING, "true" );
			}

			if ( dirtyTrackingEnabled || lazyInitializationEnabled || associationManagementEnabled ) {
				final EnhancementContext enhancementContext =
						getEnhancementContext( dirtyTrackingEnabled,
								lazyInitializationEnabled,
								associationManagementEnabled );

				// push back class transformation to the environment; for the time being this only has any effect in EE
				// container situations, calling back into PersistenceUnitInfo#addClassTransformer
				persistenceUnit.pushClassTransformer( enhancementContext );
				final ClassTransformer classTransformer = persistenceUnit.getClassTransformer();
				if ( classTransformer != null ) {
					final ClassLoader classLoader = persistenceUnit.getTempClassLoader();
					if ( classLoader == null ) {
						throw persistenceException( "Enhancement requires a temp class loader, but none was given." );
					}

					for ( Binding<JaxbHbmHibernateMapping> binding : metadataSources.getHbmXmlBindings() ) {
						final JaxbHbmHibernateMapping hibernateMapping = binding.getRoot();
						final String packageName = hibernateMapping.getPackage();
						for ( JaxbHbmRootEntityType clazz : hibernateMapping.getClazz() ) {
							final String className =
									packageName == null || packageName.isEmpty()
											? clazz.getName()
											: packageName + '.' + clazz.getName();
							try {
								classTransformer.discoverTypes( classLoader, className );
							}
							catch (EnhancementException ex) {
								log.enhancementDiscoveryFailed( className, ex );
							}
						}
					}
					for ( String annotatedClassName : metadataSources.getAnnotatedClassNames() ) {
						classTransformer.discoverTypes( classLoader, annotatedClassName );
					}
					for ( Class<?> annotatedClass : metadataSources.getAnnotatedClasses() ) {
						classTransformer.discoverTypes( classLoader, annotatedClass.getName() );
					}
				}
			}

			// for the time being we want to revoke access to the temp ClassLoader if one was passed
			metamodelBuilder.applyTempClassLoader( null );
		}
		catch (Throwable t) {
			bsr.close();
			cleanup();
			throw t;
		}
	}

	private boolean readBooleanConfigurationValueDefaultTrue(String propertyName) {
		final Object propertyValue = configurationValues.remove( propertyName );
		return propertyValue == null || parseBoolean( propertyValue.toString() );
	}

	/**
	 * Extension point for subclasses. Used by Hibernate Reactive
	 */
	protected StandardServiceRegistryBuilder getStandardServiceRegistryBuilder(BootstrapServiceRegistry bsr) {
		return StandardServiceRegistryBuilder.forJpa( bsr );
	}

	private void applyMetadataBuilderContributor() {
		final Object metadataBuilderContributorSetting = configurationValues.get( METADATA_BUILDER_CONTRIBUTOR );
		if ( metadataBuilderContributorSetting != null ) {
			final MetadataBuilderContributor metadataBuilderContributor = loadSettingInstance(
					METADATA_BUILDER_CONTRIBUTOR,
					metadataBuilderContributorSetting,
					MetadataBuilderContributor.class
			);

			if ( metadataBuilderContributor != null ) {
				metadataBuilderContributor.contribute( metamodelBuilder );
			}
		}

		metamodelBuilder.getBootstrapContext().getServiceRegistry()
				.requireService( ClassLoaderService.class )
				.loadJavaServices( MetadataBuilderContributor.class )
				.forEach( contributor -> contributor.contribute( metamodelBuilder ) );
	}

	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// temporary!
	public Map<Object,Object> getConfigurationValues() {
		return unmodifiableMap( configurationValues );
	}
	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	private boolean readBooleanConfigurationValue(String propertyName) {
		final Object propertyValue = configurationValues.remove( propertyName );
		return propertyValue != null && parseBoolean( propertyValue.toString() );
	}

	/**
	 * Builds the context to be used in runtime bytecode enhancement
	 *
	 * @param dirtyTrackingEnabled To enable dirty tracking feature
	 * @param lazyInitializationEnabled To enable lazy initialization feature
	 * @param associationManagementEnabled To enable association management feature
	 * @return An enhancement context for classes managed by this EM
	 */
	protected EnhancementContext getEnhancementContext(
			final boolean dirtyTrackingEnabled,
			final boolean lazyInitializationEnabled,
			final boolean associationManagementEnabled ) {
		return new DefaultEnhancementContext() {

			@Override
			public boolean isEntityClass(UnloadedClass classDescriptor) {
				return managedResources.getAnnotatedClassNames().contains( classDescriptor.getName() )
					&& super.isEntityClass( classDescriptor );
			}

			@Override
			public boolean isCompositeClass(UnloadedClass classDescriptor) {
				return managedResources.getAnnotatedClassNames().contains( classDescriptor.getName() )
					&& super.isCompositeClass( classDescriptor );
			}

			@Override
			public boolean doBiDirectionalAssociationManagement(UnloadedField field) {
				return associationManagementEnabled;
			}

			@Override
			public boolean doDirtyCheckingInline(UnloadedClass classDescriptor) {
				return dirtyTrackingEnabled;
			}

			@Override
			public boolean hasLazyLoadableAttributes(UnloadedClass classDescriptor) {
				return lazyInitializationEnabled;
			}

			@Override
			public boolean isLazyLoadable(UnloadedField field) {
				return lazyInitializationEnabled;
			}

			@Override
			public boolean doExtendedEnhancement(UnloadedClass classDescriptor) {
				// doesn't make any sense to have extended enhancement enabled at runtime. we only enhance entities anyway.
				return false;
			}
		};
	}

	/**
	 * Builds the {@link BootstrapServiceRegistry} used to eventually build the {@link StandardServiceRegistryBuilder}; mainly
	 * used here during instantiation to define class-loading behavior.
	 *
	 * @param integrationSettings Any integration settings passed by the EE container or SE application
	 *
	 * @return The built BootstrapServiceRegistry
	 */
	private BootstrapServiceRegistry buildBootstrapServiceRegistry(
			Map<?,?> integrationSettings,
			ClassLoader providedClassLoader,
			ClassLoaderService providedClassLoaderService) {
		final BootstrapServiceRegistryBuilder builder = new BootstrapServiceRegistryBuilder();
		applyIntegrationProvider( integrationSettings, builder );
		final StrategyRegistrationProviderList strategyRegistrationProviderList
				= (StrategyRegistrationProviderList) integrationSettings.get( STRATEGY_REGISTRATION_PROVIDERS );
		if ( strategyRegistrationProviderList != null ) {
			for ( StrategyRegistrationProvider strategyRegistrationProvider :
					strategyRegistrationProviderList.getStrategyRegistrationProviders() ) {
				builder.applyStrategySelectors( strategyRegistrationProvider );
			}
		}
		configureClassLoading( integrationSettings, providedClassLoader, providedClassLoaderService, builder );
		return builder.build();
	}

	/**
	 * @implNote {@code providedClassLoaderService} and {@code providedClassLoaders}
	 * are mutually exclusive concepts, with priority given to the former.
	 *
	 * @see BootstrapServiceRegistryBuilder#build
	 */
	private void configureClassLoading(
			Map<?, ?> integrationSettings,
			ClassLoader providedClassLoader,
			ClassLoaderService providedClassLoaderService,
			BootstrapServiceRegistryBuilder bsrBuilder) {
		if ( providedClassLoaderService != null ) {
			bsrBuilder.applyClassLoaderService( providedClassLoaderService );
			return;
		}

		if ( persistenceUnit.getClassLoader() != null ) {
			bsrBuilder.applyClassLoader( persistenceUnit.getClassLoader() );
		}

		if ( providedClassLoader != null ) {
			bsrBuilder.applyClassLoader( providedClassLoader );
		}

		final Object classLoadersSetting = integrationSettings.get( CLASSLOADERS );
		if ( classLoadersSetting != null ) {
			if ( classLoadersSetting instanceof Collection) {
				@SuppressWarnings("unchecked")
				final Collection<ClassLoader> classLoaders =
						(Collection<ClassLoader>) classLoadersSetting;
				for ( ClassLoader classLoader : classLoaders ) {
					bsrBuilder.applyClassLoader( classLoader );
				}
			}
			else if ( classLoadersSetting.getClass().isArray() ) {
				for ( ClassLoader classLoader : (ClassLoader[]) classLoadersSetting ) {
					bsrBuilder.applyClassLoader( classLoader );
				}
			}
			else if ( classLoadersSetting instanceof ClassLoader ) {
				bsrBuilder.applyClassLoader( (ClassLoader) classLoadersSetting );
			}
		}

		//configurationValues not assigned yet, using directly the properties of the PU
		final Properties puProperties = persistenceUnit.getProperties();
		if ( puProperties != null ) {
			final TcclLookupPrecedence tcclLookupPrecedence = TcclLookupPrecedence.from( puProperties );
			if ( tcclLookupPrecedence != null ) {
				bsrBuilder.applyTcclLookupPrecedence( tcclLookupPrecedence );
			}
		}
	}

	private void applyIntegrationProvider(Map<?,?> integrationSettings, BootstrapServiceRegistryBuilder bsrBuilder) {
		final Object integrationSetting = integrationSettings.get( INTEGRATOR_PROVIDER );
		if ( integrationSetting == null ) {
			return;
		}
		final IntegratorProvider integratorProvider = loadSettingInstance(
				INTEGRATOR_PROVIDER,
				integrationSetting,
				IntegratorProvider.class
		);

		if ( integratorProvider != null ) {
			for ( Integrator integrator : integratorProvider.getIntegrators() ) {
				bsrBuilder.applyIntegrator( integrator );
			}
		}
	}

	private MergedSettings mergeSettings(
			PersistenceUnitDescriptor persistenceUnit,
			Map<String,Object> integrationSettings,
			StandardServiceRegistryBuilder ssrBuilder,
			Consumer<MergedSettings> mergedSettingsBaseline) {
		final MergedSettings mergedSettings = new MergedSettings();
		if ( mergedSettingsBaseline != null ) {
			mergedSettingsBaseline.accept( mergedSettings );
		}
		mergedSettings.processPersistenceUnitDescriptorProperties( persistenceUnit );

		// see if the persistence.xml settings named a Hibernate config file....
		String cfgXmlResourceName = (String) mergedSettings.configurationValues.remove( CFG_XML_FILE );
		if ( StringHelper.isEmpty( cfgXmlResourceName ) ) {
			// see if integration settings named a Hibernate config file....
			cfgXmlResourceName = (String) integrationSettings.get( CFG_XML_FILE );
		}

		if ( isNotEmpty( cfgXmlResourceName ) ) {
			processHibernateConfigXmlResources( ssrBuilder, mergedSettings, cfgXmlResourceName );
		}

		normalizeSettings( persistenceUnit, integrationSettings, mergedSettings );

		// here we are going to iterate the merged config settings looking for:
		//		1) additional JACC permissions
		//		2) additional cache region declarations
		//
		// we will also clean up any references with null entries
		final Iterator<Map.Entry<String,Object>> iterator =
				mergedSettings.configurationValues.entrySet().iterator();
		while ( iterator.hasNext() ) {
			final Map.Entry<String,Object> entry = iterator.next();
			if ( entry.getValue() == null ) {
				// remove entries with null values
				iterator.remove();
				break;
			}

			if ( entry.getValue() instanceof String valueString ) {
				final String keyString = entry.getKey();
				if ( keyString.startsWith( CLASS_CACHE_PREFIX ) ) {
					mergedSettings.addCacheRegionDefinition(
							parseCacheRegionDefinitionEntry(
									keyString.substring( CLASS_CACHE_PREFIX.length() + 1 ),
									valueString,
									CacheRegionDefinition.CacheRegionType.ENTITY
							)
					);
				}
				else if ( keyString.startsWith( COLLECTION_CACHE_PREFIX ) ) {
					mergedSettings.addCacheRegionDefinition(
							parseCacheRegionDefinitionEntry(
									keyString.substring( COLLECTION_CACHE_PREFIX.length() + 1 ),
									(String) entry.getValue(),
									CacheRegionDefinition.CacheRegionType.COLLECTION
							)
					);
				}
			}

		}

		return mergedSettings;
	}

	/**
	 * Handles normalizing the settings coming from multiple sources, applying proper precedences
	 */
	private void normalizeSettings(
			PersistenceUnitDescriptor persistenceUnit,
			Map<String, Object> integrationSettings,
			MergedSettings mergedSettings) {
		// make a copy so we can remove things as we process them
		final HashMap<String, Object> integrationSettingsCopy = new HashMap<>( integrationSettings );

		normalizeConnectionAccessUserAndPass( integrationSettingsCopy, mergedSettings );

		normalizeTransactionCoordinator( persistenceUnit, integrationSettingsCopy, mergedSettings );

		normalizeDataAccess( integrationSettingsCopy, mergedSettings, persistenceUnit );

		// normalize ValidationMode
		final Object intgValidationMode = integrationSettingsCopy.remove( JPA_VALIDATION_MODE );
		final Object jakartaIntgValidationMode = integrationSettingsCopy.remove( JAKARTA_VALIDATION_MODE );
		if ( jakartaIntgValidationMode != null ) {
			mergedSettings.configurationValues.put( JAKARTA_VALIDATION_MODE, jakartaIntgValidationMode );
		}
		else if ( intgValidationMode != null ) {
			DEPRECATION_LOGGER.deprecatedSetting( JPA_VALIDATION_MODE, JAKARTA_VALIDATION_MODE );
			mergedSettings.configurationValues.put( JPA_VALIDATION_MODE, intgValidationMode );
		}
		else if ( persistenceUnit.getValidationMode() != null ) {
			mergedSettings.configurationValues.put( JAKARTA_VALIDATION_MODE, persistenceUnit.getValidationMode() );
		}

		// normalize SharedCacheMode
		final Object intgCacheMode = integrationSettingsCopy.remove( JPA_SHARED_CACHE_MODE );
		final Object jakartaIntgCacheMode = integrationSettingsCopy.remove( JAKARTA_SHARED_CACHE_MODE );
		if ( jakartaIntgCacheMode != null ) {
			mergedSettings.configurationValues.put( JAKARTA_SHARED_CACHE_MODE, jakartaIntgCacheMode );
		}
		else if ( intgCacheMode != null ) {
			DEPRECATION_LOGGER.deprecatedSetting( JPA_SHARED_CACHE_MODE, JAKARTA_SHARED_CACHE_MODE );
			mergedSettings.configurationValues.put( JPA_SHARED_CACHE_MODE, intgCacheMode );
		}
		else if ( persistenceUnit.getSharedCacheMode() != null ) {
			mergedSettings.configurationValues.put( JAKARTA_SHARED_CACHE_MODE, persistenceUnit.getSharedCacheMode() );
		}

		// Apply all "integration overrides" as the last step.  By specification,
		// these should have precedence.
		//
		// NOTE that this occurs after the specialized normalize calls above which remove
		// any specially-handled settings.
		for ( Map.Entry<String,Object> entry : integrationSettingsCopy.entrySet() ) {
			if ( entry.getKey() != null ) {
				if ( entry.getValue() == null ) {
					mergedSettings.configurationValues.remove( entry.getKey() );
				}
				else {
					mergedSettings.configurationValues.put( entry.getKey(), entry.getValue() );
				}
			}
		}
	}

	/**
	 * Because a DataSource can be secured (requiring Hibernate to pass the USER/PASSWORD when accessing the DataSource)
	 * we apply precedence to the USER and PASS separately
	 */
	private void normalizeConnectionAccessUserAndPass(
			HashMap<?, ?> integrationSettingsCopy,
			MergedSettings mergedSettings) {
		final Object effectiveUser = NullnessHelper.coalesceSuppliedValues(
				() -> integrationSettingsCopy.remove( USER ),
				() -> integrationSettingsCopy.remove( JAKARTA_JDBC_USER ),
				() -> {
					final Object setting = integrationSettingsCopy.remove( JPA_JDBC_USER );
					if ( setting != null ) {
						DEPRECATION_LOGGER.deprecatedSetting( JPA_JDBC_USER, JAKARTA_JDBC_USER );
					}
					return setting;
				},
				() -> extractPuProperty( persistenceUnit, USER ),
				() -> extractPuProperty( persistenceUnit, JAKARTA_JDBC_USER ),
				() -> {
					final Object setting = extractPuProperty( persistenceUnit, JPA_JDBC_USER );
					if ( setting != null ) {
						DEPRECATION_LOGGER.deprecatedSetting( JPA_JDBC_USER, JAKARTA_JDBC_USER );
					}
					return setting;
				}
		);

		final Object effectivePass = NullnessHelper.coalesceSuppliedValues(
				() -> integrationSettingsCopy.remove( PASS ),
				() -> integrationSettingsCopy.remove( JAKARTA_JDBC_PASSWORD ),
				() -> {
					final Object setting = integrationSettingsCopy.remove( JPA_JDBC_PASSWORD );
					if ( setting != null ) {
						DEPRECATION_LOGGER.deprecatedSetting( JPA_JDBC_PASSWORD, JAKARTA_JDBC_PASSWORD );
					}
					return setting;
				},
				() -> extractPuProperty( persistenceUnit, PASS ),
				() -> extractPuProperty( persistenceUnit, JAKARTA_JDBC_PASSWORD ),
				() -> {
					{
						final Object setting = extractPuProperty( persistenceUnit, JPA_JDBC_PASSWORD );
						if ( setting != null ) {
							DEPRECATION_LOGGER.deprecatedSetting( JPA_JDBC_PASSWORD, JAKARTA_JDBC_PASSWORD );
						}
						return setting;
					}
				}
		);

		if ( effectiveUser != null || effectivePass != null ) {
			applyUserAndPass( effectiveUser, effectivePass, mergedSettings );
		}
	}

	private <T> T extractPuProperty(PersistenceUnitDescriptor persistenceUnit, String propertyName) {
		final Properties properties = persistenceUnit.getProperties();
		//noinspection unchecked
		return properties == null ? null : (T) properties.get( propertyName );
	}

	private void applyUserAndPass(Object effectiveUser, Object effectivePass, MergedSettings mergedSettings) {
		final Map<String, Object> configuration = mergedSettings.configurationValues;
		if ( effectiveUser != null ) {
			configuration.put( USER, effectiveUser );
			configuration.put( JAKARTA_JDBC_USER, effectiveUser );
			configuration.put( JPA_JDBC_USER, effectiveUser );
		}
		if ( effectivePass != null ) {
			configuration.put( PASS, effectivePass );
			configuration.put( JAKARTA_JDBC_PASSWORD, effectivePass );
			configuration.put( JPA_JDBC_PASSWORD, effectivePass );
		}
	}

	private static final String IS_JTA_TXN_COORD = "local.setting.IS_JTA_TXN_COORD";

	private void normalizeTransactionCoordinator(
			PersistenceUnitDescriptor persistenceUnit,
			HashMap<?, ?> integrationSettingsCopy,
			MergedSettings mergedSettings) {
		PersistenceUnitTransactionType txnType = null;

		Object intgTxnType = integrationSettingsCopy.remove( JAKARTA_TRANSACTION_TYPE );
		if ( intgTxnType == null ) {
			intgTxnType = integrationSettingsCopy.remove( JPA_TRANSACTION_TYPE );
			if ( intgTxnType != null ) {
				DEPRECATION_LOGGER.deprecatedSetting( JPA_TRANSACTION_TYPE, JAKARTA_TRANSACTION_TYPE );
			}
		}

		if ( intgTxnType != null ) {
			txnType = PersistenceUnitTransactionTypeHelper.interpretTransactionType( intgTxnType );
		}
		else if ( persistenceUnit.getTransactionType() != null ) {
			txnType = persistenceUnit.getTransactionType();
		}
		else {
			Object puPropTxnType = mergedSettings.configurationValues.get( JAKARTA_TRANSACTION_TYPE );
			if ( puPropTxnType == null ) {
				puPropTxnType = mergedSettings.configurationValues.get( JPA_TRANSACTION_TYPE );
				if ( puPropTxnType != null ) {
					DEPRECATION_LOGGER.deprecatedSetting( JPA_TRANSACTION_TYPE, JAKARTA_TRANSACTION_TYPE );
				}
			}
			if ( puPropTxnType != null ) {
				txnType = PersistenceUnitTransactionTypeHelper.interpretTransactionType( puPropTxnType );
			}
		}

		if ( txnType == null ) {
			// is it more appropriate to have this be based on bootstrap entry point (EE vs SE)?
			log.debug( "PersistenceUnitTransactionType not specified - falling back to RESOURCE_LOCAL" );
			txnType = PersistenceUnitTransactionType.RESOURCE_LOCAL;
		}

		final boolean hasTxStrategy =
				mergedSettings.configurationValues.containsKey( TRANSACTION_COORDINATOR_STRATEGY );
		final boolean definiteJtaCoordinator;
		if ( hasTxStrategy ) {
			log.overridingTransactionStrategyDangerous( TRANSACTION_COORDINATOR_STRATEGY );
			// see if we can tell whether it is a JTA coordinator
			final Object strategy =
					mergedSettings.configurationValues.get( TRANSACTION_COORDINATOR_STRATEGY );
			definiteJtaCoordinator =
					strategy instanceof TransactionCoordinatorBuilder transactionCoordinatorBuilder
							&& transactionCoordinatorBuilder.isJta();
		}
		else {
			if ( txnType == PersistenceUnitTransactionType.JTA ) {
				mergedSettings.configurationValues.put( TRANSACTION_COORDINATOR_STRATEGY,
						JtaTransactionCoordinatorBuilderImpl.class );
				definiteJtaCoordinator = true;
			}
			else if ( txnType == PersistenceUnitTransactionType.RESOURCE_LOCAL ) {
				mergedSettings.configurationValues.put( TRANSACTION_COORDINATOR_STRATEGY,
						JdbcResourceLocalTransactionCoordinatorBuilderImpl.class );
				definiteJtaCoordinator = false;
			}
			else {
				throw new IllegalStateException( "Could not determine TransactionCoordinator strategy to use" );
			}
		}

		mergedSettings.configurationValues.put( IS_JTA_TXN_COORD, definiteJtaCoordinator );
	}

	private void normalizeDataAccess(
			HashMap<?, ?> integrationSettingsCopy,
			MergedSettings mergedSettings,
			PersistenceUnitDescriptor persistenceUnit) {
		if ( dataSource != null ) {
			// we don't explicitly know if it's JTA
			applyDataSource( dataSource, null, integrationSettingsCopy, mergedSettings );
			// EARLY EXIT!!
			return;
		}

		if ( integrationSettingsCopy.containsKey( DATASOURCE ) ) {
			final Object dataSourceRef = integrationSettingsCopy.remove( DATASOURCE );
			if ( dataSourceRef != null ) {
				applyDataSource( dataSourceRef, null, integrationSettingsCopy, mergedSettings );
				// EARLY EXIT!!
				return;
			}
		}

		if ( integrationSettingsCopy.containsKey( JAKARTA_JTA_DATASOURCE ) ) {
			final Object dataSourceRef = integrationSettingsCopy.remove( JAKARTA_JTA_DATASOURCE );
			if ( dataSourceRef != null ) {
				applyDataSource( dataSourceRef, true, integrationSettingsCopy, mergedSettings );
				// EARLY EXIT!!
				return;
			}
		}

		if ( integrationSettingsCopy.containsKey( JPA_JTA_DATASOURCE ) ) {
			DEPRECATION_LOGGER.deprecatedSetting( JPA_JTA_DATASOURCE, JAKARTA_JTA_DATASOURCE );
			final Object dataSourceRef = integrationSettingsCopy.remove( JPA_JTA_DATASOURCE );
			if ( dataSourceRef != null ) {
				applyDataSource( dataSourceRef, true,integrationSettingsCopy, mergedSettings );
				// EARLY EXIT!!
				return;
			}
		}

		if ( integrationSettingsCopy.containsKey( JAKARTA_NON_JTA_DATASOURCE ) ) {
			final Object dataSourceRef = integrationSettingsCopy.remove( JAKARTA_NON_JTA_DATASOURCE );
			applyDataSource( dataSourceRef, false, integrationSettingsCopy, mergedSettings );
			// EARLY EXIT!!
			return;
		}

		if ( integrationSettingsCopy.containsKey( JPA_NON_JTA_DATASOURCE ) ) {
			DEPRECATION_LOGGER.deprecatedSetting( JPA_NON_JTA_DATASOURCE, JAKARTA_NON_JTA_DATASOURCE );
			final Object dataSourceRef = integrationSettingsCopy.remove( JPA_NON_JTA_DATASOURCE );
			applyDataSource( dataSourceRef, false, integrationSettingsCopy, mergedSettings );
			// EARLY EXIT!!
			return;
		}

		if ( integrationSettingsCopy.containsKey( URL ) ) {
			// hibernate-specific settings have precedence over the JPA ones
			final Object integrationJdbcUrl = integrationSettingsCopy.get( URL );
			if ( integrationJdbcUrl != null ) {
				applyJdbcSettings(
						integrationJdbcUrl,
						NullnessHelper.coalesceSuppliedValues(
								() -> ConfigurationHelper.getString( DRIVER, integrationSettingsCopy ),
								() -> ConfigurationHelper.getString( JAKARTA_JDBC_DRIVER, integrationSettingsCopy ),
								() -> {
									final String driver =
											ConfigurationHelper.getString( JPA_JDBC_DRIVER, integrationSettingsCopy );
									if ( driver != null ) {
										DEPRECATION_LOGGER.deprecatedSetting( JPA_JDBC_DRIVER, JAKARTA_JDBC_DRIVER );
									}
									return driver;
								},
								() -> ConfigurationHelper.getString( DRIVER, mergedSettings.configurationValues ),
								() -> ConfigurationHelper.getString( JAKARTA_JDBC_DRIVER, mergedSettings.configurationValues ),
								() -> {
									final String driver =
											ConfigurationHelper.getString( JPA_JDBC_DRIVER, mergedSettings.configurationValues );
									if ( driver != null ) {
										DEPRECATION_LOGGER.deprecatedSetting( JPA_JDBC_DRIVER, JAKARTA_JDBC_DRIVER );
									}
									return driver;
								}
						),
						integrationSettingsCopy,
						mergedSettings
				);
				// EARLY EXIT!!
				return;
			}
		}

		if ( integrationSettingsCopy.containsKey( JAKARTA_JDBC_URL ) ) {
			final Object integrationJdbcUrl = integrationSettingsCopy.get( JAKARTA_JDBC_URL );
			if ( integrationJdbcUrl != null ) {
				applyJdbcSettings(
						integrationJdbcUrl,
						NullnessHelper.coalesceSuppliedValues(
								() -> ConfigurationHelper.getString( JAKARTA_JDBC_DRIVER, integrationSettingsCopy ),
								() -> ConfigurationHelper.getString( JAKARTA_JDBC_DRIVER, mergedSettings.configurationValues )
						),
						integrationSettingsCopy,
						mergedSettings
				);
				// EARLY EXIT!!
				return;
			}
		}

		if ( integrationSettingsCopy.containsKey( JPA_JDBC_URL ) ) {
			DEPRECATION_LOGGER.deprecatedSetting( JPA_JDBC_URL, JAKARTA_JDBC_URL );
			final Object integrationJdbcUrl = integrationSettingsCopy.get( JPA_JDBC_URL );
			if ( integrationJdbcUrl != null ) {
				applyJdbcSettings(
						integrationJdbcUrl,
						NullnessHelper.coalesceSuppliedValues(
								() -> {
									final String driver =
											ConfigurationHelper.getString( JPA_JDBC_DRIVER, integrationSettingsCopy );
									if ( driver != null ) {
										DEPRECATION_LOGGER.deprecatedSetting( JPA_JDBC_DRIVER, JAKARTA_JDBC_DRIVER );
									}
									return driver;
								},
								() -> {
									final String driver =
											ConfigurationHelper.getString( JPA_JDBC_DRIVER, mergedSettings.configurationValues );
									if ( driver != null ) {
										DEPRECATION_LOGGER.deprecatedSetting( JPA_JDBC_DRIVER, JAKARTA_JDBC_DRIVER );
									}
									return driver;
								}
						),
						integrationSettingsCopy,
						mergedSettings
				);
				// EARLY EXIT!!
				return;
			}
		}

		if ( persistenceUnit.getJtaDataSource() != null ) {
			applyDataSource( persistenceUnit.getJtaDataSource(),
					true, integrationSettingsCopy, mergedSettings );
			// EARLY EXIT!!
			return;
		}

		if ( persistenceUnit.getNonJtaDataSource() != null ) {
			applyDataSource( persistenceUnit.getNonJtaDataSource(),
					false, integrationSettingsCopy, mergedSettings );
			// EARLY EXIT!!
			return;
		}

		if ( mergedSettings.configurationValues.containsKey( URL ) ) {
			final Object url = mergedSettings.configurationValues.get( URL );
			if ( url != null && ( ! ( url instanceof String stringUrl ) || isNotEmpty( stringUrl ) ) ) {
				applyJdbcSettings(
						url,
						ConfigurationHelper.getString( DRIVER, mergedSettings.configurationValues ),
						integrationSettingsCopy,
						mergedSettings
				);
				// EARLY EXIT!!
				return;
			}
		}

		if ( mergedSettings.configurationValues.containsKey( JAKARTA_JDBC_URL ) ) {
			final Object url = mergedSettings.configurationValues.get( JAKARTA_JDBC_URL );
			if ( url != null && ( ! ( url instanceof String stringUrl ) || isNotEmpty( stringUrl ) ) ) {
				applyJdbcSettings(
						url,
						ConfigurationHelper.getString( JAKARTA_JDBC_DRIVER, mergedSettings.configurationValues ),
						integrationSettingsCopy,
						mergedSettings
				);
				// EARLY EXIT!!
				return;
			}
		}

		if ( mergedSettings.configurationValues.containsKey( JPA_JDBC_URL ) ) {
			DEPRECATION_LOGGER.deprecatedSetting( JPA_JDBC_URL, JAKARTA_JDBC_URL );
			final Object url = mergedSettings.configurationValues.get( JPA_JDBC_URL );
			if ( url != null && ( !( url instanceof String stringUrl ) || isNotEmpty( stringUrl ) ) ) {
				final String driver =
						ConfigurationHelper.getString( JPA_JDBC_DRIVER, mergedSettings.configurationValues );
				if ( driver != null ) {
					DEPRECATION_LOGGER.deprecatedSetting( JPA_JDBC_DRIVER, JAKARTA_JDBC_DRIVER );
				}
				applyJdbcSettings( url, driver, integrationSettingsCopy, mergedSettings );
				// EARLY EXIT!!
				//noinspection UnnecessaryReturnStatement
				return;
			}
		}

		// any other conditions to account for?
	}

	private void applyDataSource(
			Object dataSourceRef,
			Boolean useJtaDataSource,
			HashMap<?, ?> integrationSettingsCopy,
			MergedSettings mergedSettings) {

		// `IS_JTA_TXN_COORD` is a value set during `#normalizeTransactionCoordinator` to indicate whether
		// the execution environment "is JTA" as best as it can tell..
		//
		// we use this value when JTA was not explicitly specified in regards to the DataSource
		final boolean isJtaTransactionCoordinator =
				(Boolean) mergedSettings.configurationValues.remove( IS_JTA_TXN_COORD );
		final boolean isJta = useJtaDataSource == null ? isJtaTransactionCoordinator : useJtaDataSource;

		// add to EMF properties (questionable - see HHH-13432)
		final String emfKey;
		final String inverseEmfKey;
		final String jakartaEmfKey;
		final String jakartaInverseEmfKey;
		if ( isJta ) {
			emfKey = JPA_JTA_DATASOURCE;
			jakartaEmfKey = JAKARTA_JTA_DATASOURCE;
			inverseEmfKey = JPA_NON_JTA_DATASOURCE;
			jakartaInverseEmfKey = JAKARTA_NON_JTA_DATASOURCE;
		}
		else {
			emfKey = JPA_NON_JTA_DATASOURCE;
			jakartaEmfKey = JAKARTA_NON_JTA_DATASOURCE;
			inverseEmfKey = JPA_JTA_DATASOURCE;
			jakartaInverseEmfKey = JAKARTA_JTA_DATASOURCE;
		}
		mergedSettings.configurationValues.put( emfKey, dataSourceRef );
		mergedSettings.configurationValues.put( jakartaEmfKey, dataSourceRef );

		// clear any settings logically overridden by this datasource
		cleanUpConfigKeys(
				integrationSettingsCopy,
				mergedSettings,
				inverseEmfKey,
				jakartaInverseEmfKey,
				JPA_JDBC_DRIVER,
				JAKARTA_JDBC_DRIVER,
				DRIVER,
				JPA_JDBC_URL,
				JAKARTA_JDBC_URL,
				URL
		);


		// clean-up the entries in the "integration overrides" so they do not get get picked
		// up in the general "integration overrides" handling
		cleanUpConfigKeys(
				integrationSettingsCopy,
				DATASOURCE,
				JPA_JTA_DATASOURCE,
				JAKARTA_JTA_DATASOURCE,
				JPA_NON_JTA_DATASOURCE,
				JAKARTA_NON_JTA_DATASOURCE
		);

		// add under Hibernate's DATASOURCE setting where the ConnectionProvider will find it
		mergedSettings.configurationValues.put( DATASOURCE, dataSourceRef );
	}

	private void cleanUpConfigKeys(HashMap<?, ?> integrationSettingsCopy, MergedSettings mergedSettings, String... keys) {
		for ( String key : keys ) {
			final Object removedIntgSetting = integrationSettingsCopy.remove( key );
			if ( removedIntgSetting != null ) {
				log.debugf( "Removed integration override setting [%s] due to normalization", key );
			}

			final Object removedMergedSetting = mergedSettings.configurationValues.remove( key );
			if ( removedMergedSetting != null ) {
				log.debugf( "Removed merged setting [%s] due to normalization", key );
			}
		}
	}

	private void cleanUpConfigKeys(Map<?, ?> settings, String... keys) {
		for ( String key : keys ) {
			settings.remove( key );
		}
	}

	private void applyJdbcSettings(
			Object url,
			String driver,
			HashMap<?, ?> integrationSettingsCopy,
			MergedSettings mergedSettings) {
		mergedSettings.configurationValues.put( URL, url );
		mergedSettings.configurationValues.put( JPA_JDBC_URL, url );
		mergedSettings.configurationValues.put( JAKARTA_JDBC_URL, url );

		if ( driver != null ) {
			mergedSettings.configurationValues.put( DRIVER, driver );
			mergedSettings.configurationValues.put( JPA_JDBC_DRIVER, driver );
			mergedSettings.configurationValues.put( JAKARTA_JDBC_DRIVER, driver );
		}
		else {
			mergedSettings.configurationValues.remove( DRIVER );
			mergedSettings.configurationValues.remove( JPA_JDBC_DRIVER );
			mergedSettings.configurationValues.remove( JAKARTA_JDBC_DRIVER );
		}

		// clean up the integration-map values
		cleanUpConfigKeys(
				integrationSettingsCopy,
				DRIVER,
				JPA_JDBC_DRIVER,
				JAKARTA_JDBC_DRIVER,
				URL,
				JPA_JDBC_URL,
				JAKARTA_JDBC_URL,
				USER,
				JPA_JDBC_USER,
				JAKARTA_JDBC_USER,
				PASS,
				JPA_JDBC_PASSWORD,
				JAKARTA_JDBC_PASSWORD
		);

		cleanUpConfigKeys(
				integrationSettingsCopy,
				mergedSettings,
				DATASOURCE,
				JPA_JTA_DATASOURCE,
				JAKARTA_JTA_DATASOURCE,
				JPA_NON_JTA_DATASOURCE,
				JAKARTA_NON_JTA_DATASOURCE
		);
	}

	private void processHibernateConfigXmlResources(
			StandardServiceRegistryBuilder serviceRegistryBuilder,
			MergedSettings mergedSettings,
			String cfgXmlResourceName) {
		final LoadedConfig loadedConfig =
				serviceRegistryBuilder.getConfigLoader().loadConfigXmlResource( cfgXmlResourceName );
		mergedSettings.processHibernateConfigXmlResources( loadedConfig );
		serviceRegistryBuilder.getAggregatedCfgXml().merge( loadedConfig );
	}

	private CacheRegionDefinition parseCacheRegionDefinitionEntry(
			String role, String value, CacheRegionDefinition.CacheRegionType cacheType) {
		final StringTokenizer params = new StringTokenizer( value, ";, " );
		if ( !params.hasMoreTokens() ) {
			throw illegalUsageException( role, value, cacheType );
		}
		else {
			final String usage = params.nextToken();
			final String region = params.hasMoreTokens() ? params.nextToken() : null;
			final boolean lazyProperty =
					cacheType == CacheRegionDefinition.CacheRegionType.ENTITY
							&& ( !params.hasMoreTokens() || "all".equalsIgnoreCase( params.nextToken() ) );
			return new CacheRegionDefinition( cacheType, role, usage, region, lazyProperty );
		}
	}

	private PersistenceException illegalUsageException(
			String role, String value, CacheRegionDefinition.CacheRegionType cacheType) {
		final StringBuilder error = new StringBuilder( "Illegal usage of " );
		if ( cacheType == CacheRegionDefinition.CacheRegionType.ENTITY ) {
			error.append( CLASS_CACHE_PREFIX )
					.append( ": " )
					.append( CLASS_CACHE_PREFIX );
		}
		else {
			error.append( COLLECTION_CACHE_PREFIX )
					.append( ": " )
					.append( COLLECTION_CACHE_PREFIX );
		}
		error.append( '.' )
				.append(role)
				.append( ' ' )
				.append(value)
				.append( ".  Was expecting configuration (usage[,region[,lazy]]), but found none" );
		return persistenceException( error.toString() );
	}

	@SuppressWarnings("unchecked")
	private List<ConverterDescriptor> applyMappingResources(MetadataSources metadataSources) {
		// todo : where in the heck are `org.hibernate.jpa.boot.spi.PersistenceUnitDescriptor.getManagedClassNames` handled?!?

//		final ClassLoaderService classLoaderService = ssr.getService( ClassLoaderService.class );
//
//		// todo : make sure MetadataSources/Metadata are capable of handling duplicate sources
//
//		// explicit persistence unit mapping files listings
//		if ( persistenceUnit.getMappingFileNames() != null ) {
//			for ( String name : persistenceUnit.getMappingFileNames() ) {
//				metadataSources.addResource( name );
//			}
//		}
//
//		// explicit persistence unit managed class listings
//		//		IMPL NOTE : managed-classes can contain class or package names!!!
//		if ( persistenceUnit.getManagedClassNames() != null ) {
//			for ( String managedClassName : persistenceUnit.getManagedClassNames() ) {
//				// try it as a class name first...
//				final String classFileName = managedClassName.replace( '.', '/' ) + ".class";
//				final URL classFileUrl = classLoaderService.locateResource( classFileName );
//				if ( classFileUrl != null ) {
//					// it is a class
//					metadataSources.addAnnotatedClassName( managedClassName );
//					continue;
//				}
//
//				// otherwise, try it as a package name
//				final String packageInfoFileName = managedClassName.replace( '.', '/' ) + "/package-info.class";
//				final URL packageInfoFileUrl = classLoaderService.locateResource( packageInfoFileName );
//				if ( packageInfoFileUrl != null ) {
//					// it is a package
//					metadataSources.addPackage( managedClassName );
//					continue;
//				}
//
//				LOG.debugf(
//						"Unable to resolve class [%s] named in persistence unit [%s]",
//						managedClassName,
//						persistenceUnit.getName()
//				);
//			}
//		}

		List<ConverterDescriptor> converterDescriptors = null;

		// add any explicit Class references passed in
		final List<Class<? extends AttributeConverter<?,?>>> loadedAnnotatedClasses =
				(List<Class<? extends AttributeConverter<?,?>>>)
						configurationValues.remove( AvailableSettings.LOADED_CLASSES );
		if ( loadedAnnotatedClasses != null ) {
			for ( Class<? extends AttributeConverter<?,?>> cls : loadedAnnotatedClasses ) {
				if ( AttributeConverter.class.isAssignableFrom( cls ) ) {
					if ( converterDescriptors == null ) {
						converterDescriptors = new ArrayList<>();
					}
					final ClassmateContext classmateContext =
							metamodelBuilder.getBootstrapContext().getClassmateContext();
					converterDescriptors.add( new ClassBasedConverterDescriptor( cls, classmateContext ) );
				}
				else {
					metadataSources.addAnnotatedClass( cls );
				}
			}
		}

		// add any explicit hbm.xml references passed in
		final String explicitHbmXmls =
				(String) configurationValues.remove( AvailableSettings.HBM_XML_FILES );
		if ( explicitHbmXmls != null ) {
			for ( String hbmXml : StringHelper.split( ", ", explicitHbmXmls ) ) {
				metadataSources.addResource( hbmXml );
			}
		}

		// add any explicit orm.xml references passed in
		final List<String> explicitOrmXmlList =
				(List<String>) configurationValues.remove( AvailableSettings.ORM_XML_FILES );
		if ( explicitOrmXmlList != null ) {
			explicitOrmXmlList.forEach( metadataSources::addResource );
		}

		return converterDescriptors;
	}

	private void applyMetamodelBuilderSettings(
			MergedSettings mergedSettings,
			List<ConverterDescriptor> converterDescriptors) {
		metamodelBuilder.getBootstrapContext().markAsJpaBootstrap();

		if ( persistenceUnit.getTempClassLoader() != null ) {
			metamodelBuilder.applyTempClassLoader( persistenceUnit.getTempClassLoader() );
		}

		metamodelBuilder.applyScanEnvironment( new StandardJpaScanEnvironmentImpl( persistenceUnit ) );
		metamodelBuilder.applyScanOptions(
				new StandardScanOptions(
						(String) configurationValues.get( SCANNER_DISCOVERY ),
						persistenceUnit.isExcludeUnlistedClasses()
				)
		);

		if ( mergedSettings.cacheRegionDefinitions != null ) {
			mergedSettings.cacheRegionDefinitions.forEach( metamodelBuilder::applyCacheRegionDefinition );
		}

		applyTypeContributors();

		if ( converterDescriptors != null ) {
			converterDescriptors.forEach( metamodelBuilder::applyAttributeConverter );
		}
	}

	private void applyTypeContributors() {
		final TypeContributorList typeContributorList =
				(TypeContributorList) configurationValues.remove( TYPE_CONTRIBUTORS );
		if ( typeContributorList != null ) {
			typeContributorList.getTypeContributors().forEach( metamodelBuilder::applyTypes );
		}
		metamodelBuilder.getBootstrapContext().getServiceRegistry()
				.requireService( ClassLoaderService.class )
				.loadJavaServices( TypeContributor.class )
				.forEach( metamodelBuilder::applyTypes );
	}


	// Phase 2 concerns ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	private Object validatorFactory;
	private DataSource dataSource;
	private MetadataImplementor metadata;

	/**
	 * Intended for internal testing only...
	 */
	public MetadataImplementor getMetadata() {
		return metadata;
	}

	@Override
	public ManagedResources getManagedResources() {
		return managedResources;
	}

	/**
	 * Used by extensions : Hibernate Reactive
	 */
	@Override
	public MetadataImplementor metadata() {
		if ( metadata == null ) {
			metadata =
					MetadataBuildingProcess.complete( managedResources,
							metamodelBuilder.getBootstrapContext(),
							metamodelBuilder.getMetadataBuildingOptions() );
		}
		return metadata;
	}

	@Override
	public EntityManagerFactoryBuilder withValidatorFactory(Object validatorFactory) {
		this.validatorFactory = validatorFactory;
		if ( validatorFactory != null ) {
			BeanValidationIntegrator.validateFactory( validatorFactory );
		}
		return this;
	}

	@Override
	public EntityManagerFactoryBuilder withDataSource(DataSource dataSource) {
		this.dataSource = dataSource;
		return this;
	}

	@Override
	public void cancel() {
		cleanup();
		// todo : close the bootstrap registry (not critical, but nice to do)
	}

	private void cleanup() {
		// Stop and de-register the ConnectionProvider to prevent connections lying around
		if ( standardServiceRegistry instanceof ServiceRegistryImplementor serviceRegistry
				&& standardServiceRegistry instanceof ServiceBinding.ServiceLifecycleOwner lifecycleOwner ) {
			final ServiceBinding<ConnectionProvider> binding =
					serviceRegistry.locateServiceBinding( ConnectionProvider.class );
			if ( binding != null && binding.getService() instanceof Stoppable ) {
				lifecycleOwner.stopService( binding );
				binding.setService( null );
			}
		}
	}

	@Override
	public void generateSchema() {
		// This seems overkill, but building the SF is necessary to get the Integrators to kick in.
		// Metamodel will clean this up...
		try {
			populateSessionFactoryBuilder( metadata().getSessionFactoryBuilder(), standardServiceRegistry );
			SchemaManagementToolCoordinator.process( metadata, standardServiceRegistry, configurationValues,
					DelayedDropRegistryNotAvailableImpl.INSTANCE );
		}
		catch (Exception e) {
			throw persistenceException( "Error performing schema management", e );
		}
		finally {
			// release this builder
			cancel();
		}
	}

	@Override
	public EntityManagerFactory build() {
		boolean success = false;
		try {
			final SessionFactoryBuilder sessionFactoryBuilder = metadata().getSessionFactoryBuilder();
			populateSessionFactoryBuilder( sessionFactoryBuilder, standardServiceRegistry );
			try {
				final EntityManagerFactory entityManagerFactory = sessionFactoryBuilder.build();
				success = true;
				return entityManagerFactory;
			}
			catch (Exception e) {
				throw persistenceException( "Unable to build Hibernate SessionFactory", e );
			}
		}
		finally {
			if ( !success ) {
				cleanup();
			}
		}
	}

	protected void populateSessionFactoryBuilder(SessionFactoryBuilder builder, StandardServiceRegistry registry) {

//		// Locate and apply the requested SessionFactory-level interceptor (if one)
//		final Object sessionFactoryInterceptorSetting = configurationValues.remove( org.hibernate.cfg.AvailableSettings.INTERCEPTOR );
//		if ( sessionFactoryInterceptorSetting != null ) {
//			final Interceptor sessionFactoryInterceptor =
//					strategySelector.resolveStrategy( Interceptor.class, sessionFactoryInterceptorSetting );
//			sfBuilder.applyInterceptor( sessionFactoryInterceptor );
//		}

		// will use user override value or default to false if not supplied to follow JPA spec.
		final boolean jtaTransactionAccessEnabled =
				readBooleanConfigurationValue( AvailableSettings.ALLOW_JTA_TRANSACTION_ACCESS );
		if ( !jtaTransactionAccessEnabled ) {
			( ( SessionFactoryBuilderImplementor ) builder ).disableJtaTransactionAccess();
		}

		final boolean allowRefreshDetachedEntity =
				readBooleanConfigurationValue( AvailableSettings.ALLOW_REFRESH_DETACHED_ENTITY );
		if ( !allowRefreshDetachedEntity ) {
			( (SessionFactoryBuilderImplementor) builder ).disableRefreshDetachedEntity();
		}

		// Locate and apply any requested SessionFactoryObserver
		final Object sessionFactoryObserverSetting =
				configurationValues.remove( AvailableSettings.SESSION_FACTORY_OBSERVER );
		if ( sessionFactoryObserverSetting != null ) {
			final SessionFactoryObserver suppliedSessionFactoryObserver =
					registry.requireService( StrategySelector.class )
							.resolveStrategy( SessionFactoryObserver.class, sessionFactoryObserverSetting );
			builder.addSessionFactoryObservers( suppliedSessionFactoryObserver );
		}

		builder.addSessionFactoryObservers( ServiceRegistryCloser.INSTANCE );

		builder.applyEntityNotFoundDelegate( JpaEntityNotFoundDelegate.INSTANCE );

		if ( validatorFactory != null ) {
			builder.applyValidatorFactory( validatorFactory );
		}
	}


	private static class ServiceRegistryCloser implements SessionFactoryObserver {
		/**
		 * Singleton access
		 */
		public static final ServiceRegistryCloser INSTANCE = new ServiceRegistryCloser();

		@Override
		public void sessionFactoryCreated(SessionFactory sessionFactory) {
			// nothing to do
		}

		@Override
		public void sessionFactoryClosed(SessionFactory sessionFactory) {
			final SessionFactoryImplementor factoryImplementor = (SessionFactoryImplementor) sessionFactory;
			final ServiceRegistryImplementor serviceRegistry = factoryImplementor.getServiceRegistry();
			serviceRegistry.destroy();
			final ServiceRegistryImplementor basicRegistry =
					(ServiceRegistryImplementor) serviceRegistry.getParentServiceRegistry();
			basicRegistry.destroy();
		}
	}

	private PersistenceException persistenceException(String message) {
		return persistenceException( message, null );
	}

	protected PersistenceException persistenceException(String message, Exception cause) {
		return new PersistenceException( getExceptionHeader() + message, cause );
	}

	private String getExceptionHeader() {
		return "[PersistenceUnit: " + persistenceUnit.getName() + "] ";
	}

	public static class MergedSettings {
		private final Map<String,Object> configurationValues = new ConcurrentHashMap<>( 16, 0.75f, 1 );

		private List<CacheRegionDefinition> cacheRegionDefinitions;

		/**
		 * 	MergedSettings is initialized with hibernate.properties
		 */
		private MergedSettings() {
			configurationValues.putAll( PropertiesHelper.map( Environment.getProperties() ) );
		}

		public void processPersistenceUnitDescriptorProperties(PersistenceUnitDescriptor persistenceUnit) {
			if ( persistenceUnit.getProperties() != null ) {
				configurationValues.putAll( PropertiesHelper.map( persistenceUnit.getProperties() ) );
			}
			configurationValues.put( PERSISTENCE_UNIT_NAME, persistenceUnit.getName() );
		}

		public void processHibernateConfigXmlResources(LoadedConfig loadedConfig){
			if ( !configurationValues.containsKey( SESSION_FACTORY_NAME ) ) {
				// there is not already a SF-name in the merged settings
				final String sessionFactoryName = loadedConfig.getSessionFactoryName();
				if ( sessionFactoryName != null ) {
					// but the cfg.xml file we are processing named one..
					configurationValues.put( SESSION_FACTORY_NAME, sessionFactoryName );
				}
			}
//			else {
				// make sure they match?
//			}
			configurationValues.putAll( loadedConfig.getConfigurationValues() );
		}

		public Map<String,Object> getConfigurationValues() {
			return configurationValues;
		}

		private void addCacheRegionDefinition(CacheRegionDefinition cacheRegionDefinition) {
			if ( cacheRegionDefinitions == null ) {
				cacheRegionDefinitions = new ArrayList<>();
			}
			cacheRegionDefinitions.add( cacheRegionDefinition );
		}
	}

	@SuppressWarnings("unchecked")
	private <T> T loadSettingInstance(String settingName, Object settingValue, Class<T> clazz) {
		final Class<? extends T> instanceClass;
		if ( clazz.isAssignableFrom( settingValue.getClass() ) ) {
			return (T) settingValue;
		}
		else if ( settingValue instanceof Class ) {
			instanceClass = (Class<? extends T>) settingValue;
		}
		else if ( settingValue instanceof String className ) {
			if ( standardServiceRegistry != null ) {
				instanceClass =
						standardServiceRegistry.requireService( ClassLoaderService.class )
								.classForName( className );
			}
			else {
				try {
					instanceClass = (Class<? extends T>) Class.forName( className );
				}
				catch (ClassNotFoundException e) {
					throw new IllegalArgumentException( "Can't load class: " + className, e );
				}
			}
		}
		else {
			throw new IllegalArgumentException( "The provided " + settingName
					+ " setting value [" + settingValue + "] is not supported" );
		}

		if ( instanceClass != null ) {
			try {
				return instanceClass.newInstance();
			}
			catch (InstantiationException | IllegalAccessException e) {
				throw new IllegalArgumentException(
						"The " + clazz.getSimpleName() +" class [" + instanceClass + "] could not be instantiated",
						e
				);
			}
		}
		else {
			return null;
		}
	}

	/**
	 * Exposed to extensions: see Hibernate Reactive
	 */
	protected StandardServiceRegistry getStandardServiceRegistry() {
		return standardServiceRegistry;
	}
}
