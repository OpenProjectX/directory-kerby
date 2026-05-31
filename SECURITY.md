# Security Policy

## Reporting a Vulnerability

Apache Directory follows the [ASF security process](https://www.apache.org/security/). Report privately to
`security@apache.org` (PMC: `private@directory.apache.org`); do not open public issues/PRs for security reports.

## Threat Model

`apache/directory-kerby` is a Kerberos implementation (KDC, client, crypto, PKINIT/token preauth) within the Apache Directory project. Its security context is covered by the Apache
Directory umbrella threat model (Kerberos addendum (K)): https://github.com/apache/directory-server/blob/master/THREAT_MODEL.md
