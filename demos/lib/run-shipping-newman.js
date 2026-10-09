#!/usr/bin/env node
// demos/lib/run-shipping-newman.js — runs the behavior-equivalence suite
// (tooling/newman/mea.postman_collection.json) with the `shippingSagaEnabled`
// COLLECTION variable patched to a given value, used by
// demos/demo-shipping-cutover.sh (shipping-plan.md S8, ch.24, DRQ-065).
//
// WHY THIS EXISTS (a documented, empirically verified finding, not an
// assumption): the collection's "SF-gate" item (Scenario 4 — Shipping-
// Failure) reads `pm.collectionVariables.get('shippingSagaEnabled')`. The
// newman CLI's `--env-var` / `--global-var` flags populate the ENVIRONMENT
// and GLOBAL variable scopes respectively — a DIFFERENT scope from the
// collection-level `variable` array a collection's own JSON defines.
// `newman run ... --env-var "shippingSagaEnabled=true"` was verified by
// hand (ahead of writing this script) to leave the SF-gate PENDING, because
// `--env-var` never reaches `pm.collectionVariables`. See
// examples/06-shipping-service/CUTOVER.md for the full writeup.
//
// This script works around that CLI limitation using newman's documented
// Node API: it loads the collection JSON into a plain in-memory object,
// patches that object's `variable` array entry for `shippingSagaEnabled`,
// and passes the IN-MEMORY object (not a file path) to `newman.run()`'s
// `collection` option. The committed collection file on disk is never
// written to — this is a runtime-only override, exactly as flipping a
// `-D`/env-var flag is for the monolith/proxy.
//
// Usage: node run-shipping-newman.js <projectRoot> <baseUrl> <sagaEnabled:true|false> [folderName]
// Exit code: 0 if newman reports zero failures, 1 if any assertion failed,
// 2 on a setup/script error (collection shape changed, newman not found).

const fs = require("fs");
const path = require("path");

function resolveNewman() {
    // Prefer a normally resolvable `newman` (project/local node_modules,
    // or NODE_PATH) so this script works unmodified on any machine where
    // `npm install newman` was done the ordinary way.
    try {
        return require("newman");
    } catch (e) {
        // Fall through to common global-install locations.
    }
    // NEWMAN_MODULE_DIR: the pinned npx package demos/lib/newman.sh
    // resolves when no newman is installed (DRQ-077).
    const candidates = [
        ...(process.env.NEWMAN_MODULE_DIR ? [process.env.NEWMAN_MODULE_DIR] : []),
        path.join(process.env.HOME || "", ".local/lib/node_modules/newman"),
        "/usr/local/lib/node_modules/newman",
        "/usr/lib/node_modules/newman",
    ];
    for (const candidate of candidates) {
        try {
            return require(candidate);
        } catch (e) {
            // try the next candidate
        }
    }
    console.error(
        "Could not resolve the 'newman' module via require('newman') or any known global " +
            "install location (" + candidates.join(", ") + "). Install it with " +
            "'npm install -g newman' or 'npm install newman' in this project, or run " +
            "this helper through a demo script (demos/lib/newman.sh fetches the pinned npx package)."
    );
    process.exit(2);
}

const [, , projectRoot, baseUrl, sagaEnabledRaw, folder] = process.argv;

if (!projectRoot || !baseUrl || !sagaEnabledRaw) {
    console.error("usage: run-shipping-newman.js <projectRoot> <baseUrl> <true|false> [folderName]");
    process.exit(2);
}

const newman = resolveNewman();

const collectionPath = path.join(projectRoot, "tooling/newman/mea.postman_collection.json");
const environmentPath = path.join(projectRoot, "tooling/newman/local.postman_environment.json");

const collection = JSON.parse(fs.readFileSync(collectionPath, "utf8"));
const environment = JSON.parse(fs.readFileSync(environmentPath, "utf8"));

const variable = (collection.variable || []).find((item) => item.key === "shippingSagaEnabled");
if (!variable) {
    console.error(
        "shippingSagaEnabled collection variable not found in " + collectionPath +
            " — has the collection shape changed since shipping-plan S2?"
    );
    process.exit(2);
}
variable.value = sagaEnabledRaw;

const runOptions = {
    collection: collection,
    environment: environment,
    envVar: [{ key: "baseUrl", value: baseUrl }],
    reporters: "cli",
};
if (folder) {
    runOptions.folder = folder;
}

newman.run(runOptions, function (err, summary) {
    if (err) {
        console.error("newman run errored:", err);
        process.exit(2);
    }
    process.exit(summary.run.failures.length > 0 ? 1 : 0);
});
