# Glivit — Fleet Access Policy (§3 security fix)

Closes the driver IDOR called out in the spec: a `DRIVER` user holds
`view_live_location`, and device detail / position / playback endpoints
previously checked only **tenant** scope — so a driver could reach any device
in the tenant by guessing its id. Access now flows through assignments.

## Model (Flyway `V4__fleet_access_policy.sql`)
- `drivers.user_id` — links a driver record to a login.
- `vehicle_driver_assignments` — which driver currently drives which vehicle
  (active flag, source, assigned_by, start/end, audit timestamps).
- `user_project_assignments` — restricts a non-admin user to specific projects.

Device access resolves as **user → driver → vehicle → device** or
**user → project → device**.

## `FleetAccessPolicy`
Central, reusable authorization (`com.glivt.access.FleetAccessPolicy`):
- `deviceScope(user)` → `DeviceScope` (unrestricted for `view_all_vehicles`
  holders; otherwise the explicit device-id allow-list).
- `requireDeviceAccess(user, deviceId)` — tenant **and** assignment check;
  out-of-scope ids throw **404** (not 403) to prevent enumeration.
- `requireVehicleAccess`, `requireProjectAccess`, `requireDriverAccess`.

Rules: Super Admin / Tenant Admin (`view_all_vehicles`) → whole tenant;
project-scoped user → assigned projects only; driver → currently assigned
vehicle/device only.

## Wiring (this increment)
Enforced on the endpoints a driver can actually call:
- `GET /api/devices/{id}` (detail)
- `GET /api/devices/{id}/positions`
- `GET /api/devices/{id}/playback`
- `GET /api/devices` list results are scoped by `deviceScope` as defence in depth.

The same policy is the intended gate for events, commands, reports, live
streams and every AI tool — those are wired in later increments.

## Tests (`FleetAccessPolicyTest`, H2)
- Driver reaches only the assigned device across detail/positions/playback;
  an unassigned device in the **same tenant** returns 404 on all three.
- Project-scoped user reaches only devices in the assigned project.
- Admin reaches every device in the tenant.

## Not yet done (follow-ups)
Driver-scoped device **listing** (drivers currently still need
`view_all_vehicles` to hit the list endpoint), and wiring the policy into
event/command/report/live/AI paths. The recommended `driver_user_links`
multi-assignment table is represented here by `drivers.user_id` +
`vehicle_driver_assignments`; a dedicated link table can be added if multiple
concurrent logins per driver become a requirement.
