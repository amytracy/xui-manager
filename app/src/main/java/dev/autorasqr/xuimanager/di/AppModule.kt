package dev.autorasqr.xuimanager.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.autorasqr.xuimanager.data.db.AppDatabase
import dev.autorasqr.xuimanager.data.db.DeployJobDao
import dev.autorasqr.xuimanager.data.db.ServerDao
import dev.autorasqr.xuimanager.data.deploy.DeployLauncher
import dev.autorasqr.xuimanager.data.geo.CountryLookup
import dev.autorasqr.xuimanager.data.geo.HttpCountryLookup
import dev.autorasqr.xuimanager.data.repository.AppUpdateRepositoryImpl
import dev.autorasqr.xuimanager.data.repository.ClientRepositoryImpl
import dev.autorasqr.xuimanager.data.repository.DeployRepositoryImpl
import dev.autorasqr.xuimanager.data.repository.InboundEditorRepositoryImpl
import dev.autorasqr.xuimanager.data.repository.InboundRepositoryImpl
import dev.autorasqr.xuimanager.data.repository.NodeRepositoryImpl
import dev.autorasqr.xuimanager.data.repository.PanelStatusRepositoryImpl
import dev.autorasqr.xuimanager.data.repository.PanelUpdateRepositoryImpl
import dev.autorasqr.xuimanager.data.repository.PanelWebRepositoryImpl
import dev.autorasqr.xuimanager.data.repository.RestoreRepositoryImpl
import dev.autorasqr.xuimanager.data.repository.RoutingRepositoryImpl
import dev.autorasqr.xuimanager.data.repository.ServerPowerRepositoryImpl
import dev.autorasqr.xuimanager.data.repository.ServerRepositoryImpl
import dev.autorasqr.xuimanager.data.repository.SubscriptionRepositoryImpl
import dev.autorasqr.xuimanager.data.repository.SystemUpdateRepositoryImpl
import dev.autorasqr.xuimanager.data.repository.UninstallRepositoryImpl
import dev.autorasqr.xuimanager.data.repository.XrayUpdateRepositoryImpl
import dev.autorasqr.xuimanager.data.secure.KeystoreSecretStore
import dev.autorasqr.xuimanager.data.secure.SecretStore
import dev.autorasqr.xuimanager.domain.repository.AppUpdateRepository
import dev.autorasqr.xuimanager.domain.repository.ClientRepository
import dev.autorasqr.xuimanager.domain.repository.DeployRepository
import dev.autorasqr.xuimanager.domain.repository.InboundEditorRepository
import dev.autorasqr.xuimanager.domain.repository.InboundRepository
import dev.autorasqr.xuimanager.domain.repository.NodeRepository
import dev.autorasqr.xuimanager.domain.repository.PanelStatusRepository
import dev.autorasqr.xuimanager.domain.repository.PanelUpdateRepository
import dev.autorasqr.xuimanager.domain.repository.PanelWebRepository
import dev.autorasqr.xuimanager.domain.repository.RestoreRepository
import dev.autorasqr.xuimanager.domain.repository.RoutingRepository
import dev.autorasqr.xuimanager.domain.repository.ServerPowerRepository
import dev.autorasqr.xuimanager.domain.repository.ServerRepository
import dev.autorasqr.xuimanager.domain.repository.SubscriptionRepository
import dev.autorasqr.xuimanager.domain.repository.SystemUpdateRepository
import dev.autorasqr.xuimanager.domain.repository.UninstallRepository
import dev.autorasqr.xuimanager.domain.repository.XrayUpdateRepository
import dev.autorasqr.xuimanager.service.ServiceDeployLauncher
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "xui-manager.db").build()

    @Provides
    fun serverDao(db: AppDatabase): ServerDao = db.serverDao()

    @Provides
    fun deployJobDao(db: AppDatabase): DeployJobDao = db.deployJobDao()

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun secretStore(impl: KeystoreSecretStore): SecretStore
    @Binds abstract fun serverRepository(impl: ServerRepositoryImpl): ServerRepository
    @Binds abstract fun deployRepository(impl: DeployRepositoryImpl): DeployRepository
    @Binds abstract fun clientRepository(impl: ClientRepositoryImpl): ClientRepository
    @Binds abstract fun inboundRepository(impl: InboundRepositoryImpl): InboundRepository
    @Binds abstract fun routingRepository(impl: RoutingRepositoryImpl): RoutingRepository
    @Binds abstract fun inboundEditorRepository(impl: InboundEditorRepositoryImpl): InboundEditorRepository
    @Binds abstract fun nodeRepository(impl: NodeRepositoryImpl): NodeRepository
    @Binds abstract fun panelStatusRepository(impl: PanelStatusRepositoryImpl): PanelStatusRepository
    @Binds abstract fun panelWebRepository(impl: PanelWebRepositoryImpl): PanelWebRepository
    @Binds abstract fun panelUpdateRepository(impl: PanelUpdateRepositoryImpl): PanelUpdateRepository
    @Binds abstract fun systemUpdateRepository(impl: SystemUpdateRepositoryImpl): SystemUpdateRepository
    @Binds abstract fun serverPowerRepository(impl: ServerPowerRepositoryImpl): ServerPowerRepository
    @Binds abstract fun subscriptionRepository(impl: SubscriptionRepositoryImpl): SubscriptionRepository
    @Binds abstract fun uninstallRepository(impl: UninstallRepositoryImpl): UninstallRepository
    @Binds abstract fun restoreRepository(impl: RestoreRepositoryImpl): RestoreRepository
    @Binds abstract fun xrayUpdateRepository(impl: XrayUpdateRepositoryImpl): XrayUpdateRepository
    @Binds abstract fun appUpdateRepository(impl: AppUpdateRepositoryImpl): AppUpdateRepository
    @Binds abstract fun deployLauncher(impl: ServiceDeployLauncher): DeployLauncher
    @Binds abstract fun countryLookup(impl: HttpCountryLookup): CountryLookup
}
