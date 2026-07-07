"""Single source of truth for the package version.

This value feeds both the package metadata (via ``[tool.hatch.version]`` in
``pyproject.toml``) and the ``User-Agent`` header (see ``_constants.py``).
"""

__version__ = "0.1.0"
