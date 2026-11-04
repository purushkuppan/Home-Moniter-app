# 1. Java and Spring Boot for the backend

Date: 2026-11-03 · Status: accepted

## Context
The core will grow into multi-home logic, roles, a rules engine and many connectors. Code is
written mostly by AI agents and reviewed by the owner, who is most fluent in Java. Node.js was
considered for its small footprint and shared TypeScript with the apps.

## Decision
Java 25 (OpenJDK/Temurin) with Spring Boot 4 for the core platform. Apps stay in TypeScript;
the event contract will be shared through JSON Schema code generation.

## Consequences
Strong typing and stable libraries for long-term complexity, and faster review of agent code.
The JVM needs a capped heap (384 MB) on the 4 GB hub.
