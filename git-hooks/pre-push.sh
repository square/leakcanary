#!/bin/sh

echo "Running static analysis..."

# Run static analysis tools
./gradlew check

status=$?

if [ "$status" = 0 ] ; then
    echo "Static analysis found no problems."
    exit 0
else
    echo 1>&2 "Static analysis found violations! Fix then before pushing your code!"
    echo "See generated reports above or in /<project_dir>/build/reports folder"
    exit 1
fi