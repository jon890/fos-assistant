"""관리 bootstrap용 일반 runtime addon. PluginManager에는 등록하지 않는다."""

from .bodyless_install import InstallationError, install_bodyless_writer

__all__ = ("InstallationError", "install_bodyless_writer")
