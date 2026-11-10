// The published event contract shared by the ledger and its consumers. No dependencies: plain data classes,
// so a consumer never has to pull in the ledger's code to read its events.
plugins {
    id("ledger.kotlin-conventions")
}
