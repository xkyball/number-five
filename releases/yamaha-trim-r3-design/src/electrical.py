"""R3 electrical entry point: real typed KiCad check and diagnostic SPICE."""
import kicad_audit, spice_checks
if __name__ == '__main__':
 kicad_audit.main()
 spice_checks.main()
