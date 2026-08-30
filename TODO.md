# Informal TODO list

This is for capturing brief descriptions of future development tasks. It is _not_ for detailed specifications
or comprehensive task tracking.

[ ] refactor main-worker API so that common middleware-like functions (like same-origin checks) are
    actually handled by middleware instead of duplicated checks in relevant handlers.
[ ] factor out common styling for main-worker browser UI as its own CSS file, and use it to style auth.html