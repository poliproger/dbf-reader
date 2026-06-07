# Synthetic test fixtures

These `.dbf` files are committed so tests are self-contained and never depend on the
gitignored `examples/` directory.

## `employee.dbf`

A small dBASE III file written with javadbf (`DBFWriter`, UTF-8), 6 fields / 3 records:

| Field  | Type      | Len,Dec |
|--------|-----------|---------|
| NAME   | CHARACTER | 20      |
| DEPT   | CHARACTER | 12      |
| AGE    | NUMERIC   | 3,0     |
| SALARY | NUMERIC   | 10,2    |
| ACTIVE | LOGICAL   | 1       |
| HIRED  | DATE      | 8       |

Records:

1. `Alice Smith`, `Engineering`, 34, 5400.00, true, 2019-03-12
2. `Bob Jones`, `Sales`, 45, 4800.50, false, 2015-11-02
3. `Carol White`, `Support`, 29, 3900.00, true, 2021-07-21

Used by `DbfDocumentTest.readsSyntheticSampleFile`. To regenerate, write a tiny `DBFWriter`
program with the schema above (see `DbfRoundTripTest.sampleDbf()` for the same pattern) and
dump its bytes to this path.