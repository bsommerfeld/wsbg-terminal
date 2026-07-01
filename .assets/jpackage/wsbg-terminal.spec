# Custom jpackage rpm spec (picked up via --resource-dir .assets/jpackage;
# the override file name must be <package-name>.spec, i.e. wsbg-terminal.spec).
#
# VERBATIM copy of the JDK 25 default template.spec with ONE addition, marked
# "WSBG:" below: a postun section that wipes the per-user application data on
# a real uninstall (arg 1 == 0), never on an upgrade (arg 1 == 1). When bumping
# the CI JDK major version, re-diff against that JDK's template.spec.
# NOTE: rpm expands macros even inside comments, so no percent signs here.

Summary: APPLICATION_SUMMARY
Name: APPLICATION_PACKAGE
Version: APPLICATION_VERSION
Release: APPLICATION_RELEASE
License: APPLICATION_LICENSE_TYPE
Vendor: APPLICATION_VENDOR

%if "xAPPLICATION_URL" != "x"
URL: APPLICATION_URL
%endif

%if "xAPPLICATION_PREFIX" != "x"
Prefix: APPLICATION_PREFIX
%endif

Provides: APPLICATION_PACKAGE

%if "xAPPLICATION_GROUP" != "x"
Group: APPLICATION_GROUP
%endif

Autoprov: 0
Autoreq: 0
%if "xPACKAGE_DEFAULT_DEPENDENCIES" != "x" || "xPACKAGE_CUSTOM_DEPENDENCIES" != "x"
Requires: PACKAGE_DEFAULT_DEPENDENCIES PACKAGE_CUSTOM_DEPENDENCIES
%endif

#comment line below to enable effective jar compression
#it could easily get your package size from 40 to 15Mb but
#build time will substantially increase and it may require unpack200/system java to install
%define __jar_repack %{nil}

# on RHEL we got unwanted improved debugging enhancements
%define _build_id_links none

%define package_filelist %{_builddir}/%{name}.files
%define app_filelist %{_builddir}/%{name}.app.files
%define filesystem_filelist %{_builddir}/%{name}.filesystem.files

%define default_filesystem / /opt /usr /usr/bin /usr/lib /usr/local /usr/local/bin /usr/local/lib

%description
APPLICATION_DESCRIPTION

%global __os_install_post %{nil}

%prep

%build

%install
rm -rf %{buildroot}
install -d -m 755 %{buildroot}APPLICATION_DIRECTORY
cp -r %{_sourcedir}APPLICATION_DIRECTORY/* %{buildroot}APPLICATION_DIRECTORY
if [ "$(echo %{_sourcedir}/lib/systemd/system/*.service)" != '%{_sourcedir}/lib/systemd/system/*.service' ]; then
  install -d -m 755 %{buildroot}/lib/systemd/system
  cp %{_sourcedir}/lib/systemd/system/*.service %{buildroot}/lib/systemd/system
fi
%if "xAPPLICATION_LICENSE_FILE" != "x"
  %define license_install_file %{_defaultlicensedir}/%{name}-%{version}/%{basename:APPLICATION_LICENSE_FILE}
  install -d -m 755 "%{buildroot}%{dirname:%{license_install_file}}"
  install -m 644 "APPLICATION_LICENSE_FILE" "%{buildroot}%{license_install_file}"
%endif
(cd %{buildroot} && find . -path ./lib/systemd -prune -o -type d -print) | sed -e 's/^\.//' -e '/^$/d' | sort > %{app_filelist}
{ rpm -ql filesystem || echo %{default_filesystem}; } | sort > %{filesystem_filelist}
comm -23 %{app_filelist} %{filesystem_filelist} > %{package_filelist}
sed -i -e 's/.*/%dir "&"/' %{package_filelist}
(cd %{buildroot} && find . -not -type d) | sed -e 's/^\.//' -e 's/.*/"&"/' >> %{package_filelist}
%if "xAPPLICATION_LICENSE_FILE" != "x"
  sed -i -e 's|"%{license_install_file}"||' -e '/^$/d' %{package_filelist}
%endif

%files -f %{package_filelist}
%if "xAPPLICATION_LICENSE_FILE" != "x"
  %license "%{license_install_file}"
%endif

%post
package_type=rpm
LAUNCHER_AS_SERVICE_SCRIPTS
DESKTOP_COMMANDS_INSTALL
LAUNCHER_AS_SERVICE_COMMANDS_INSTALL

%pre
package_type=rpm
COMMON_SCRIPTS
LAUNCHER_AS_SERVICE_SCRIPTS
if [ "$1" -gt 1 ]; then
  :; LAUNCHER_AS_SERVICE_COMMANDS_UNINSTALL
fi

%preun
package_type=rpm
COMMON_SCRIPTS
DESKTOP_SCRIPTS
LAUNCHER_AS_SERVICE_SCRIPTS
DESKTOP_COMMANDS_UNINSTALL
LAUNCHER_AS_SERVICE_COMMANDS_UNINSTALL


%postun
# WSBG: wipe the per-user app data (config, Ollama store, JCEF cache,
# headline archive) on a real uninstall only, never on an upgrade. Best
# effort: covers the DEFAULT XDG_DATA_HOME location for every local user
# incl. root.
if [ "$1" -eq 0 ]; then
  for dir in /home/*/.local/share/wsbg-terminal /root/.local/share/wsbg-terminal; do
    [ -d "$dir" ] && rm -rf "$dir" || :
  done
fi

%clean
