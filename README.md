# Moneybags microservices

Start the complete local demo from this directory in PowerShell:

```powershell
./start.ps1
```

Requires Java JDK 17+, Maven 3.9+, Node.js 20+ and PowerShell 5.1 or 7 on Windows. The first build downloads Maven/npm dependencies. The script verifies the build and tests, then launches the local database, six services (identity, banking, payments, loans, audit/compliance and reporting), API gateway and frontend. It waits for every service to become ready and cleans up a failed start.

Open [the application](http://localhost:5173). Synthetic local users `customer`, `customer2`, `admin` and `checker` use `LocalBanking!2026`. Local data resets when the stack stops. Stop all recorded processes with `./stop.ps1`.

Use `./start.ps1 -Offline` with cached dependencies, or `./start.ps1 -SkipBuild` after a successful verified build. The latter rejects stale sources or changed artifacts. Service logs and process records are in `Downloads/integrated/.runtime`.

See [architecture and verification](Downloads/integrated/docs/MICROSERVICES.md) for service ownership, ports, testing and the remaining shared database coupling. See [the product guide](Downloads/integrated/README.md) for the existing workflows and Oracle setup.

Banking is organized into customer, product, account, ledger, treasury, card and deposit libraries. Payments and loan servicing reuse the ledger library inside their own database transactions. See the [test report](Downloads/integrated/docs/MICROSERVICES-TEST-REPORT.md) for the full workflow and outage results.
