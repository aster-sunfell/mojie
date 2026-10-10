let blocked = false

export function setMaintenanceBlocked(value) { blocked = value }
export function maintenanceBlocked() { return blocked }
export function reportMaintenance() {
  blocked = true
  window.dispatchEvent(new Event('mojie:maintenance'))
}
