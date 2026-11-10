rootProject.name = "ledger-service"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(
    ":ledger:domain",
    ":ledger:application",
    ":ledger:adapters:postgres",
    ":ledger:adapters:rest",
    ":ledger:adapters:kafka",
    ":ledger:app",
    ":contracts:events",
    ":consumers:activity-projection",
    ":consumers:fraud-alerts",
    ":architecture-tests",
)
