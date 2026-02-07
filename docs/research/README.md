# Research Documentation

This directory contains detailed design specifications and implementation examples that are too detailed for the main docs.

## Index

### Core Design

- **[BlobRef-Design.md](BlobRef-Design.md)** - Complete BlobRef specification
  - Structure, serialization format, validation rules
  - Why no extension field, why isContainer flag
  - Changes from vault-mvp

- **[ObjectStorage-API.md](ObjectStorage-API.md)** - ObjectStorage interface design
  - Reactive APIs (Multi<> not List<>)
  - Tenant-first parameter ordering
  - BinaryData abstraction
  - Compression transparency

- **[Compression.md](Compression.md)** - Compression strategy
  - Why compression is driver detail
  - When to compress (MIME type heuristics)
  - Filesystem vs MinIO vs S3 behaviors

- **[Database-Schema.md](Database-Schema.md)** - Complete database schema
  - Table definitions with rationale
  - Why no extension/stored_bytes columns
  - Indexes and example queries
  - Changes from vault-mvp

## When to Add Research Docs

Add research docs when:
- Implementation examples are too detailed for main docs
- Design decisions need extensive justification
- Multiple implementation strategies need comparison
- API design needs detailed examples

Keep main docs high-level, link to research for details.

## See Also

Main documentation in [/docs](../)
