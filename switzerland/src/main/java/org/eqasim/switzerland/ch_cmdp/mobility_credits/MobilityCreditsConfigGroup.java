package org.eqasim.switzerland.ch_cmdp.mobility_credits;

import java.util.Map;

import org.matsim.core.config.Config;
import org.matsim.core.config.ReflectiveConfigGroup;

/**
 * Structural settings of the mobility-credit scheme. Tunable numbers live in {@link MobilityCreditsParameters}.
 * <p>
 * The group is always present in the config with {@code enabled = false}, so a run without changes is identical
 * to the baseline. Enable from the command line with {@code --config:mobilityCredits.enabled true}.
 */
public final class MobilityCreditsConfigGroup extends ReflectiveConfigGroup {
	public static final String GROUP_NAME = "mobilityCredits";

	private boolean enabled = false;
	private String parametersPath = null;
	private String congestionPath = null;
	private String residentAttribute = "cantonName";
	private String residentValue = "fribourg";

	public MobilityCreditsConfigGroup() {
		super(GROUP_NAME);
	}

	public static MobilityCreditsConfigGroup get(Config config) {
		return (MobilityCreditsConfigGroup) config.getModules().get(GROUP_NAME);
	}

	@StringGetter("enabled")
	public boolean isEnabled() {
		return enabled;
	}

	@StringSetter("enabled")
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	@StringGetter("parametersPath")
	public String getParametersPath() {
		return parametersPath;
	}

	@StringSetter("parametersPath")
	public void setParametersPath(String parametersPath) {
		this.parametersPath = emptyToNull(parametersPath);
	}

	@StringGetter("congestionPath")
	public String getCongestionPath() {
		return congestionPath;
	}

	@StringSetter("congestionPath")
	public void setCongestionPath(String congestionPath) {
		this.congestionPath = emptyToNull(congestionPath);
	}

	@StringGetter("residentAttribute")
	public String getResidentAttribute() {
		return residentAttribute;
	}

	@StringSetter("residentAttribute")
	public void setResidentAttribute(String residentAttribute) {
		this.residentAttribute = residentAttribute;
	}

	@StringGetter("residentValue")
	public String getResidentValue() {
		return residentValue;
	}

	@StringSetter("residentValue")
	public void setResidentValue(String residentValue) {
		this.residentValue = residentValue;
	}

	private static String emptyToNull(String value) {
		return value == null || value.isBlank() || value.equals("null") ? null : value;
	}

	@Override
	public Map<String, String> getComments() {
		Map<String, String> comments = super.getComments();
		comments.put("enabled", "Activates the mobility-credit scheme. Default: false (baseline behaviour).");
		comments.put("parametersPath",
				"Optional yml file with credit parameters (e.g. 'car.constant_credits: 1.5'), relative to the config. "
						+ "Single values can also be set with --credit-parameter:<name> <value>.");
		comments.put("congestionPath",
				"CSV with link_id,time_bin_start_s,time_bin_end_s,congestion_ratio_per_link (may be gzipped), "
						+ "relative to the config. Empty: no congestion pricing.");
		comments.put("residentAttribute", "Person attribute that identifies participants of the scheme.");
		comments.put("residentValue",
				"Value of residentAttribute for participants. Everyone else is exempt (unlimited credits).");
		return comments;
	}
}
